package com.wavesplatform.state.diffs

import cats.{Order => _, _}
import com.wavesplatform.account.{AddressScheme, PrivateKeyAccount}
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.lang.directives.DirectiveParser
import com.wavesplatform.lang.v1.ScriptEstimator
import com.wavesplatform.lang.v1.compiler.Terms.TRUE
import com.wavesplatform.lang.v1.compiler.{CompilerContext, CompilerV1}
import com.wavesplatform.settings.{Constants, TestFunctionalitySettings}
import com.wavesplatform.state._
import com.wavesplatform.state.diffs.TransactionDiffer.TransactionValidationError
import com.wavesplatform.transaction.ValidationError.AccountBalanceError
import com.wavesplatform.transaction.assets.exchange._
import com.wavesplatform.transaction.assets.{IssueTransaction, IssueTransactionV1, IssueTransactionV2}
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.smart.script.v1.ScriptV1
import com.wavesplatform.transaction.smart.script.{Script, ScriptCompiler}
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{GenesisTransaction, ValidationError}
import com.wavesplatform.utils.functionCosts
import com.wavesplatform.{NoShrink, TransactionGen}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Inside, Matchers, PropSpec}

class ExchangeTransactionDiffTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with Inside with NoShrink {

  val fs = TestFunctionalitySettings.Enabled.copy(
    preActivatedFeatures = Map(BlockchainFeatures.SmartAccounts.id -> 0, BlockchainFeatures.SmartAccountsTrades.id -> 0))

  property("preserves waves invariant, stores match info, rewards matcher") {

    val preconditionsAndExchange: Gen[(GenesisTransaction, GenesisTransaction, IssueTransaction, IssueTransaction, ExchangeTransaction)] = for {
      buyer  <- accountGen
      seller <- accountGen
      ts     <- timestampGen
      gen1: GenesisTransaction = GenesisTransaction.create(buyer, ENOUGH_AMT, ts).explicitGet()
      gen2: GenesisTransaction = GenesisTransaction.create(seller, ENOUGH_AMT, ts).explicitGet()
      issue1: IssueTransaction <- issueReissueBurnGeneratorP(ENOUGH_AMT, seller).map(_._1).retryUntil(_.script.isEmpty)
      issue2: IssueTransaction <- issueReissueBurnGeneratorP(ENOUGH_AMT, buyer).map(_._1).retryUntil(_.script.isEmpty)
      maybeAsset1              <- Gen.option(issue1.id())
      maybeAsset2              <- Gen.option(issue2.id()) suchThat (x => x != maybeAsset1)
      exchange                 <- exchangeGeneratorP(buyer, seller, maybeAsset1, maybeAsset2)
    } yield (gen1, gen2, issue1, issue2, exchange)

    forAll(preconditionsAndExchange) {
      case ((gen1, gen2, issue1, issue2, exchange)) =>
        assertDiffAndState(Seq(TestBlock.create(Seq(gen1, gen2, issue1, issue2))), TestBlock.create(Seq(exchange)), fs) {
          case (blockDiff, state) =>
            val totalPortfolioDiff: Portfolio = Monoid.combineAll(blockDiff.portfolios.values)
            totalPortfolioDiff.balance shouldBe 0
            totalPortfolioDiff.effectiveBalance shouldBe 0
            totalPortfolioDiff.assets.values.toSet shouldBe Set(0L)

            blockDiff.portfolios(exchange.sender).balance shouldBe exchange.buyMatcherFee + exchange.sellMatcherFee - exchange.fee
        }
    }
  }

  property("can't trade from scripted account") {

    val fs = TestFunctionalitySettings.Enabled.copy(preActivatedFeatures = Map(BlockchainFeatures.SmartAccounts.id -> 0))

    val preconditionsAndExchange
      : Gen[(GenesisTransaction, GenesisTransaction, SetScriptTransaction, IssueTransaction, IssueTransaction, ExchangeTransaction)] = for {
      version <- Gen.oneOf(SetScriptTransaction.supportedVersions.toSeq)
      buyer   <- accountGen
      seller  <- accountGen
      fee     <- smallFeeGen
      ts      <- timestampGen
      gen1: GenesisTransaction = GenesisTransaction.create(buyer, ENOUGH_AMT, ts).explicitGet()
      gen2: GenesisTransaction = GenesisTransaction.create(seller, ENOUGH_AMT, ts).explicitGet()
      setScript                = SetScriptTransaction.selfSigned(version, seller, Some(ScriptV1(TRUE).explicitGet()), fee, ts).explicitGet()
      issue1: IssueTransaction <- issueReissueBurnGeneratorP(ENOUGH_AMT, seller).map(_._1).retryUntil(_.script.isEmpty)
      issue2: IssueTransaction <- issueReissueBurnGeneratorP(ENOUGH_AMT, buyer).map(_._1).retryUntil(_.script.isEmpty)
      maybeAsset1              <- Gen.option(issue1.id())
      maybeAsset2              <- Gen.option(issue2.id()) suchThat (x => x != maybeAsset1)
      exchange                 <- exchangeV1GeneratorP(buyer, seller, maybeAsset1, maybeAsset2).filter(_.version == 1)
    } yield (gen1, gen2, setScript, issue1, issue2, exchange)

    forAll(preconditionsAndExchange) {
      case ((gen1, gen2, setScript, issue1, issue2, exchange)) =>
        assertLeft(Seq(TestBlock.create(Seq(gen1, gen2, setScript, issue1, issue2))), TestBlock.create(Seq(exchange)), fs)(
          "can't participate in ExchangeTransaction")
    }
  }

  property("buy waves without enough money for fee") {
    val preconditions: Gen[(GenesisTransaction, GenesisTransaction, IssueTransactionV1, ExchangeTransaction)] = for {
      buyer  <- accountGen
      seller <- accountGen
      ts     <- timestampGen
      gen1: GenesisTransaction = GenesisTransaction.create(buyer, 1 * Constants.UnitsInWave, ts).explicitGet()
      gen2: GenesisTransaction = GenesisTransaction.create(seller, ENOUGH_AMT, ts).explicitGet()
      issue1: IssueTransactionV1 <- issueGen(buyer)
      exchange <- Gen.oneOf(
        exchangeV1GeneratorP(buyer, seller, None, Some(issue1.id()), fixedMatcherFee = Some(300000)),
        exchangeV2GeneratorP(buyer, seller, None, Some(issue1.id()), fixedMatcherFee = Some(300000))
      )
    } yield {
      (gen1, gen2, issue1, exchange)
    }

    forAll(preconditions) {
      case ((gen1, gen2, issue1, exchange)) =>
        whenever(exchange.amount > 300000) {
          assertDiffAndState(Seq(TestBlock.create(Seq(gen1, gen2, issue1))), TestBlock.create(Seq(exchange)), fs) {
            case (blockDiff, _) =>
              val totalPortfolioDiff: Portfolio = Monoid.combineAll(blockDiff.portfolios.values)
              totalPortfolioDiff.balance shouldBe 0
              totalPortfolioDiff.effectiveBalance shouldBe 0
              totalPortfolioDiff.assets.values.toSet shouldBe Set(0L)

              blockDiff.portfolios(exchange.sender).balance shouldBe exchange.buyMatcherFee + exchange.sellMatcherFee - exchange.fee
          }
        }
    }
  }

  def createExTx(buy: Order, sell: Order, price: Long, matcher: PrivateKeyAccount, ts: Long): Either[ValidationError, ExchangeTransaction] = {
    val mf     = buy.matcherFee
    val amount = math.min(buy.amount, sell.amount)
    ExchangeTransactionV1.create(
      matcher = matcher,
      buyOrder = buy.asInstanceOf[OrderV1],
      sellOrder = sell.asInstanceOf[OrderV1],
      price = price,
      amount = amount,
      buyMatcherFee = (BigInt(mf) * amount / buy.amount).toLong,
      sellMatcherFee = (BigInt(mf) * amount / sell.amount).toLong,
      fee = buy.matcherFee,
      timestamp = ts
    )
  }

  property("small fee cases") {
    val MatcherFee = 300000L
    val Ts         = 1000L

    val preconditions: Gen[(PrivateKeyAccount, PrivateKeyAccount, PrivateKeyAccount, GenesisTransaction, GenesisTransaction, IssueTransactionV1)] =
      for {
        buyer   <- accountGen
        seller  <- accountGen
        matcher <- accountGen
        ts      <- timestampGen
        gen1: GenesisTransaction = GenesisTransaction.create(buyer, ENOUGH_AMT, ts).explicitGet()
        gen2: GenesisTransaction = GenesisTransaction.create(seller, ENOUGH_AMT, ts).explicitGet()
        issue1: IssueTransactionV1 <- issueGen(seller)
      } yield (buyer, seller, matcher, gen1, gen2, issue1)

    forAll(preconditions, priceGen) {
      case ((buyer, seller, matcher, gen1, gen2, issue1), price) =>
        val assetPair = AssetPair(Some(issue1.id()), None)
        val buy       = Order.buy(buyer, matcher, assetPair, price, 1000000L, Ts, Ts + 1, MatcherFee)
        val sell      = Order.sell(seller, matcher, assetPair, price, 1L, Ts, Ts + 1, MatcherFee)
        val tx        = createExTx(buy, sell, price, matcher, Ts).explicitGet()
        assertDiffAndState(Seq(TestBlock.create(Seq(gen1, gen2, issue1))), TestBlock.create(Seq(tx)), fs) {
          case (blockDiff, state) =>
            blockDiff.portfolios(tx.sender).balance shouldBe tx.buyMatcherFee + tx.sellMatcherFee - tx.fee
            state.portfolio(tx.sender).balance shouldBe 0L
        }
    }
  }

  property("Not enough balance") {
    val MatcherFee = 300000L
    val Ts         = 1000L

    val preconditions: Gen[(PrivateKeyAccount, PrivateKeyAccount, PrivateKeyAccount, GenesisTransaction, GenesisTransaction, IssueTransactionV1)] =
      for {
        buyer   <- accountGen
        seller  <- accountGen
        matcher <- accountGen
        ts      <- timestampGen
        gen1: GenesisTransaction = GenesisTransaction.create(buyer, ENOUGH_AMT, ts).explicitGet()
        gen2: GenesisTransaction = GenesisTransaction.create(seller, ENOUGH_AMT, ts).explicitGet()
        issue1: IssueTransactionV1 <- issueGen(seller, fixedQuantity = Some(1000L))
      } yield (buyer, seller, matcher, gen1, gen2, issue1)

    forAll(preconditions, priceGen) {
      case ((buyer, seller, matcher, gen1, gen2, issue1), price) =>
        val assetPair = AssetPair(Some(issue1.id()), None)
        val buy       = Order.buy(buyer, matcher, assetPair, price, issue1.quantity + 1, Ts, Ts + 1, MatcherFee)
        val sell      = Order.sell(seller, matcher, assetPair, price, issue1.quantity + 1, Ts, Ts + 1, MatcherFee)
        val tx        = createExTx(buy, sell, price, matcher, Ts).explicitGet()
        assertDiffEi(Seq(TestBlock.create(Seq(gen1, gen2, issue1))), TestBlock.create(Seq(tx)), fs) { totalDiffEi =>
          inside(totalDiffEi) {
            case Left(TransactionValidationError(AccountBalanceError(errs), _)) =>
              errs should contain key seller.toAddress
          }
        }
    }
  }

  def compile(scriptText: String, ctx: CompilerContext): Either[String, (Script, Long)] = {
    val compiler = new CompilerV1(ctx)

    val directives = DirectiveParser(scriptText)

    val scriptWithoutDirectives =
      scriptText.lines
        .filter(str => !str.contains("{-#"))
        .mkString("\n")

    for {
      expr       <- compiler.compile(scriptWithoutDirectives, directives)
      script     <- ScriptV1(expr)
      complexity <- ScriptEstimator(functionCosts, expr)
    } yield (script, complexity)
  }

  property("ExchangeTransactions valid if all scripts succeeds") {
    val allValidP = smartTradePreconditions(
      scriptGen("Order", true),
      scriptGen("Order", true),
      scriptGen("ExchangeTransaction", true)
    )

    forAll(allValidP) {
      case (genesis, List(tr1, tr2), List(asset1, asset2), setMatcherScript, exchangeTx) =>
        val preconBlocks = Seq(
          TestBlock.create(Seq(genesis)),
          TestBlock.create(Seq(tr1, tr2)),
          TestBlock.create(Seq(asset1, asset2, setMatcherScript))
        )
        assertDiffEi(preconBlocks, TestBlock.create(Seq(exchangeTx))) { diff =>
          diff.isRight shouldBe true
        }
    }
  }

  property("ExchangeTransactions invalid if one of order scripts fails") {
    val fs = TestFunctionalitySettings.Enabled

    val failedOrderScript = smartTradePreconditions(
      scriptGen("Order", false),
      scriptGen("Order", true),
      scriptGen("ExchangeTransaction", true)
    )

    forAll(failedOrderScript) {
      case (genesis, List(tr1, tr2), List(asset1, asset2), setMatcherScript, exchangeTx) =>
        val preconBlocks = Seq(
          TestBlock.create(Seq(genesis)),
          TestBlock.create(Seq(tr1, tr2)),
          TestBlock.create(Seq(asset1, asset2, setMatcherScript))
        )
        assertLeft(preconBlocks, TestBlock.create(Seq(exchangeTx)), fs)("TransactionNotAllowedByScript")
    }
  }

  property("ExchangeTransactions invalid if matcher script fails") {
    val fs = TestFunctionalitySettings.Enabled

    val failedMatcherScript = smartTradePreconditions(
      scriptGen("Order", true),
      scriptGen("Order", true),
      scriptGen("ExchangeTransaction", false)
    )

    forAll(failedMatcherScript) {
      case (genesis, List(tr1, tr2), List(asset1, asset2), setMatcherScript, exchangeTx) =>
        val preconBlocks = Seq(
          TestBlock.create(Seq(genesis)),
          TestBlock.create(Seq(tr1, tr2)),
          TestBlock.create(Seq(asset1, asset2, setMatcherScript))
        )
        assertLeft(preconBlocks, TestBlock.create(Seq(exchangeTx)), fs)("TransactionNotAllowedByScript")
    }
  }

  def scriptGen(caseType: String, v: Boolean): String = {
    s"""
       |match tx {
       | case _: $caseType => $v
       | case _ => ${!v}
       |}
      """.stripMargin
  }

  def smartTradePreconditions(
      priceAssetScript: String,
      amountAssetScript: String,
      txScript: String): Gen[(GenesisTransaction, List[TransferTransaction], List[IssueTransaction], SetScriptTransaction, ExchangeTransaction)] = {
    val enoughFee = 500000

    val txScriptCompiled = ScriptCompiler(txScript).explicitGet()._1

    val amountAssetScriptCompiled = Some(ScriptCompiler(amountAssetScript).explicitGet()._1)
    val priceAssetScriptCompiled  = Some(ScriptCompiler(priceAssetScript).explicitGet()._1)

    val chainId = AddressScheme.current.chainId

    for {
      master    <- accountGen
      fstIssuer <- accountGen
      sndIssuer <- accountGen
      ts        <- timestampGen
      genesis = GenesisTransaction.create(master, Long.MaxValue, ts).explicitGet()
      tr1     = createWavesTransfer(master, fstIssuer.toAddress, Long.MaxValue / 3, enoughFee, ts + 1).explicitGet()
      tr2     = createWavesTransfer(master, sndIssuer.toAddress, Long.MaxValue / 3, enoughFee, ts + 2).explicitGet()
      asset1 = IssueTransactionV2
        .selfSigned(2: Byte, chainId, fstIssuer, "Asset#1".getBytes, "".getBytes, 1000000, 8, false, priceAssetScriptCompiled, enoughFee, ts + 3)
        .explicitGet()
      asset2 = IssueTransactionV2
        .selfSigned(2: Byte, chainId, sndIssuer, "Asset#2".getBytes, "".getBytes, 1000000, 8, false, priceAssetScriptCompiled, enoughFee, ts + 4)
        .explicitGet()
      setMatcherScript = SetScriptTransaction
        .selfSigned(1: Byte, master, Some(txScriptCompiled), enoughFee, ts + 5)
        .explicitGet()
      assetPair = AssetPair(Some(asset1.id()), Some(asset2.id()))
      o1 <- Gen.oneOf(
        OrderV1.buy(fstIssuer, master, assetPair, 100000000, 100000000, ts + 6, ts + 10000, enoughFee),
        OrderV2.buy(fstIssuer, master, assetPair, 100000000, 100000000, ts + 6, ts + 10000, enoughFee)
      )
      o2 <- Gen.oneOf(
        OrderV1.sell(fstIssuer, master, assetPair, 100000000, 100000000, ts + 7, ts + 10000, enoughFee),
        OrderV2.sell(fstIssuer, master, assetPair, 100000000, 100000000, ts + 7, ts + 10000, enoughFee)
      )
      exchangeTx = {
        ExchangeTransactionV2
          .create(master, o1, o2, 100000000, 100000000, enoughFee, enoughFee, enoughFee, ts + 8)
          .explicitGet()
      }
    } yield (genesis, List(tr1, tr2), List(asset1, asset2), setMatcherScript, exchangeTx)
  }

}
