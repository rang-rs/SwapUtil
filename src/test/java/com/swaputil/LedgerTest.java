package com.swaputil;

import java.math.BigDecimal;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LedgerTest
{
	static Ledger configured()
	{
		Ledger ledger = new Ledger();
		ledger.group = "Me = My main, My alt\nAlex = Alex main, Alex alt\nSam = Sam main\nPat = Pat main";
		ledger.setMode("My main", 0);
		ledger.setMode("My alt", 1);
		return ledger;
	}

	static Ledger.Trade trade(Ledger ledger, String owner, int mode, long given, long received, String... people)
	{
		Ledger.Trade trade = new Ledger.Trade();
		trade.account = "My main";
		trade.operator = owner;
		trade.counterparty = "Customer";
		trade.mode = mode;
		trade.given = given;
		trade.received = received;
		trade.ratePeriod = ledger.rates().id;
		trade.participants = List.of(people);
		ledger.add(trade);
		return trade;
	}

	@Test
	public void dividesBothSidesWithoutConvertingCurrencies()
	{
		Ledger ledger = configured();
		trade(ledger, "Me", 0, 0, 100_000_000, "Alex", "Me");
		trade(ledger, "Me", 1, 1_300_000_000L, 0, "Alex", "Me");
		List<Balances.Payment> payments = Balances.payments(ledger, ledger.current().id);
		assertEquals(2, payments.size());
		assertEquals("Me", payments.get(0).payer());
		assertEquals("Alex", payments.get(0).payee());
		assertEquals(new BigDecimal("50000000"), payments.get(0).amount.rounded().abs());
		assertEquals("Alex", payments.get(1).payer());
		assertEquals("Me", payments.get(1).payee());
		assertEquals(new BigDecimal("650000000"), payments.get(1).amount.rounded().abs());
	}

	@Test
	public void offsetsOppositeObligationsAcrossOperators()
	{
		Ledger ledger = configured();
		trade(ledger, "Me", 0, 0, 100, "Alex", "Me");
		trade(ledger, "Alex", 0, 0, 40, "Alex", "Me");
		Balances.Payment payment = Balances.payments(ledger, ledger.current().id).get(0);
		assertEquals("Me", payment.payer());
		assertEquals(new BigDecimal("30"), payment.amount.rounded().abs());
	}

	@Test
	public void capitalisationChangesDoNotSplitTheSamePeopleIntoDifferentBalances()
	{
		Ledger ledger = configured();
		trade(ledger, "My main", 0, 0, 100, "Alex main", "My main");
		trade(ledger, "alex_main", 0, 0, 40, "MY MAIN", "alex_main");
		List<Balances.Payment> payments = Balances.payments(ledger, ledger.current().id);
		assertEquals(1, payments.size());
		assertEquals("My main", payments.get(0).payer());
		assertEquals(new BigDecimal("30"), payments.get(0).amount.rounded().abs());
		assertEquals(1, Balances.buckets(ledger, ledger.current().id).size());
		assertEquals(2, Balances.buckets(ledger, ledger.current().id).get(0).count);
	}

	@Test
	public void retainsFractionsAcrossManyThreeWayTrades()
	{
		Ledger ledger = configured();
		for (int i = 0; i < 300; i++) { trade(ledger, "Me", 0, 0, 1, "Alex", "Me", "Sam"); }
		for (Balances.Payment payment : Balances.payments(ledger, ledger.current().id))
		{
			assertEquals(new BigDecimal("100"), payment.amount.rounded().abs());
		}
	}

	@Test
	public void keepsGrossVolumeWhenNetIsZero()
	{
		Ledger ledger = configured();
		trade(ledger, "Me", 0, 100, 0, "Me");
		trade(ledger, "Me", 0, 0, 100, "Me");
		Balances.Bucket bucket = Balances.buckets(ledger, ledger.current().id).get(0);
		assertEquals(100, bucket.received[0]);
		assertEquals(100, bucket.given[0]);
		assertTrue(Balances.payments(ledger, ledger.current().id).isEmpty());
	}

	@Test
	public void partnerTransfersDoNotAffectCustomerVolumeOrShares()
	{
		Ledger ledger = configured();
		Ledger.Trade transfer = trade(ledger, "Me", 0, 0, 100, "Alex", "Me");
		transfer.partnerTransfer = true;
		assertTrue(Balances.buckets(ledger, ledger.current().id).isEmpty());
		assertTrue(Balances.payments(ledger, ledger.current().id).isEmpty());
		assertEquals(1, ledger.trades.size());
	}

	@Test
	public void startsNewRatePeriodsEvenWhenReturningToPreviousRates()
	{
		Ledger ledger = configured();
		Ledger.Trade first = trade(ledger, "Me", 0, 0, 100, "Alex", "Me");
		ledger.configure(ledger.group, "OSRS", "Hardcore", "1000", "1100");
		trade(ledger, "Me", 0, 0, 100, "Alex", "Me");
		ledger.configure(ledger.group, "OSRS", "Hardcore", "900", "1500");
		trade(ledger, "Me", 0, 0, 100, "Alex", "Me");
		assertEquals(3, ledger.rates.size());
		assertEquals(3, Balances.buckets(ledger, ledger.current().id).size());
		assertEquals(ledger.rates.get(0).id, first.ratePeriod);
		assertNotEquals(first.ratePeriod, ledger.rates().id);
	}

	@Test
	public void unchangedRatesDoNotCreatePeriod()
	{
		Ledger ledger = configured();
		ledger.configure(ledger.group, "OSRS", "Hardcore", "900.00", "1500");
		assertEquals(1, ledger.rates.size());
	}

	@Test
	public void settlementPreservesHistoryAndRates()
	{
		Ledger ledger = configured();
		trade(ledger, "Me", 0, 0, 100, "Alex", "Me");
		String previous = ledger.current().id;
		String rates = ledger.rates().id;
		ledger.settle(0);
		assertEquals(1, ledger.trades.size());
		assertTrue(ledger.settlements.get(0).closedAt > 0);
		assertTrue(Balances.payments(ledger, ledger.current().id).isEmpty());
		assertEquals(1, Balances.payments(ledger, previous).size());
		assertEquals(rates, ledger.rates().id);
	}

	@Test
	public void lateSaveCarriesUnaccountedTradeIntoTheNewSettlement()
	{
		Ledger ledger = configured();
		Ledger.Trade late = new Ledger.Trade();
		late.account = "My main";
		late.operator = "Me";
		late.counterparty = "Customer";
		late.mode = 0;
		late.received = 100;
		late.ratePeriod = ledger.rates().id;
		late.participants = List.of("Alex", "Me");
		late.time = 1;
		ledger.settle(0);
		ledger.add(late);
		assertTrue(late.carriedForward);
		assertEquals(ledger.current().id, late.settlement);
		assertEquals(1, Balances.payments(ledger, ledger.current().id).size());
	}

	@Test
	public void incomingTradesDoNotPreventSettingsChanges()
	{
		Ledger ledger = configured();
		ledger.revision = 10;
		ledger.configure(ledger.group, "OSRS", "Hardcore", "1000", "1100");
		assertEquals("1100", ledger.rates().sell);
	}

	@Test
	public void independentRateUpdatesPreserveTheOtherClientsChanges()
	{
		Ledger ledger = configured();
		String group = ledger.group;
		String originalPeriod = ledger.rates().id;
		ledger.configure(null, null, null, "1000", null);
		ledger.configure(null, null, null, null, "1100");
		assertEquals("1000", ledger.rates().buy);
		assertEquals("1100", ledger.rates().sell);
		assertEquals(group, ledger.group);
		assertEquals(3, ledger.rates.size());
		assertEquals(originalPeriod, ledger.rates.get(0).id);
		assertEquals("900", ledger.rates.get(0).buy);
		assertEquals("1500", ledger.rates.get(0).sell);
	}

	@Test(expected = IllegalStateException.class)
	public void refusesSettlementAfterAnotherClientChangesRecords()
	{
		Ledger ledger = configured();
		ledger.revision = 5;
		ledger.settle(4);
	}

	@Test
	public void duplicateTradeIsOnlyAddedOnce()
	{
		Ledger ledger = configured();
		Ledger.Trade trade = trade(ledger, "Me", 0, 0, 100, "Me");
		ledger.add(trade);
		assertEquals(1, ledger.trades.size());
	}

	@Test
	public void deduplicatesAltsAndIgnoresUnlistedFriendsChatMembers()
	{
		Group group = Group.parse(configured().group);
		assertEquals(List.of("Alex", "Me"), group.participants("MY_MAIN", List.of("My alt", "Alex main", "Alex alt", "Stranger")));
		assertEquals(List.of("Me"), group.participants("My main", List.of()));
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesAmbiguousAliases()
	{
		Group.parse("Alex = Alex_main\nSam = alex main");
	}

	@Test
	public void acceptsOneRsnPerLine()
	{
		Group group = Group.parse("Rang swaps\n\nAlex main\nSam main");
		assertEquals("Rang swaps", group.person("rang_swaps"));
		assertEquals(List.of("Alex main", "Rang swaps"),
			group.participants("Rang swaps", List.of("ALEX MAIN", "Stranger")));
		assertEquals(List.of("Rang swaps"), group.participants("Rang swaps", List.of()));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsDuplicateRsnsRegardlessOfCaseOrSpaces()
	{
		Group.parse("Rang swaps\nRANG_SWAPS");
	}

	@Test
	public void preservesIndependentAccountModes()
	{
		Ledger ledger = configured();
		assertEquals(Integer.valueOf(0), ledger.accountModes.get("my main"));
		assertEquals(Integer.valueOf(1), ledger.accountModes.get("my alt"));
		ledger.setMode("My main", -1);
		assertFalse(ledger.accountModes.containsKey("my main"));
		assertEquals(Integer.valueOf(1), ledger.accountModes.get("my alt"));
	}
}
