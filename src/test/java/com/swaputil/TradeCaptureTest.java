package com.swaputil;

import java.util.List;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

public class TradeCaptureTest
{
	@Test
	public void recordsTradesRegardlessOfRsnCapitalisation()
	{
		Ledger ledger = new Ledger();
		ledger.group = "rang swaps\nalex main";
		ledger.setMode("Rang swaps", 0);
		TradeCapture capture = new TradeCapture();
		capture.begin(ledger, "RANG SWAPS", 301);
		capture.opponent("Customer");
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 1)});
		capture.confirm();
		Ledger.Trade trade = capture.complete(List.of("ALEX MAIN", "Rang Swaps"), 1);
		assertNotNull(trade);
		assertEquals("rang swaps", trade.operator);
		assertEquals(List.of("alex main", "rang swaps"), trade.participants);
		assertEquals(1, trade.given);
	}

	private TradeCapture start()
	{
		TradeCapture capture = new TradeCapture();
		capture.begin(LedgerTest.configured(), "My main", 301);
		capture.opponent("Customer");
		return capture;
	}

	@Test
	public void capturesFinalOfferAndIgnoresContainerClearAfterConfirmation()
	{
		TradeCapture capture = start();
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 250)});
		capture.offer(false, new Item[]{new Item(ItemID.PLATINUM, 3_000_000)});
		capture.confirm();
		capture.offer(false, new Item[0]);
		capture.closed(true, 10);
		Ledger.Trade trade = capture.complete(List.of("Alex alt"), 1234);
		assertEquals(250, trade.given);
		assertEquals(3_000_000_000L, trade.received);
		assertEquals(1234, trade.time);
		assertEquals(List.of("Alex", "Me"), trade.participants);
		assertNull(capture.complete(List.of(), 1234));
	}

	@Test
	public void doesNotTreatAnOfferAsACompletedTrade()
	{
		TradeCapture capture = start();
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		assertNull(capture.complete(List.of(), 1));
	}

	@Test
	public void clearedAndExpiredTradesCannotBeRecorded()
	{
		TradeCapture capture = start();
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		capture.confirm();
		capture.closed(true, 10);
		capture.tick(13);
		assertNull(capture.complete(List.of(), 1));
		capture = start();
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		capture.confirm();
		capture.clear();
		assertNull(capture.complete(List.of(), 1));
	}

	@Test
	public void offerRemovalBeforeConfirmationChangesTheTotal()
	{
		TradeCapture capture = start();
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		capture.offer(true, new Item[0]);
		capture.offer(false, new Item[]{new Item(ItemID.COINS, 200)});
		capture.confirm();
		Ledger.Trade trade = capture.complete(List.of(), 1);
		assertEquals(0, trade.given);
		assertEquals(200, trade.received);
	}

	@Test
	public void partnersAreTransfersEvenWhenAbsentFromFriendsChat()
	{
		TradeCapture capture = start();
		capture.opponent("ALEX ALT");
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		capture.confirm();
		Ledger.Trade trade = capture.complete(List.of(), 1);
		assertTrue(trade.partnerTransfer);
		assertEquals(List.of("Me"), trade.participants);
	}

	@Test
	public void keepsTheRatePeriodFromWhenTheTradeOpened()
	{
		Ledger ledger = LedgerTest.configured();
		TradeCapture capture = new TradeCapture();
		capture.begin(ledger, "My main", 301);
		capture.opponent("Customer");
		String original = ledger.rates().id;
		ledger.configure(ledger.group, "OSRS", "Hardcore", "1000", "1100");
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		capture.confirm();
		assertEquals(original, capture.complete(List.of(), 1).ratePeriod);
	}

	@Test
	public void unknownModeOrAccountDoesNotCreateMislabeledRecords()
	{
		TradeCapture capture = new TradeCapture();
		capture.begin(LedgerTest.configured(), "New account", 301);
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		capture.confirm();
		assertNull(capture.complete(List.of(), 1));
	}

	@Test
	public void flagsUnvaluedItemsWithoutTreatingThemAsGp()
	{
		TradeCapture capture = start();
		capture.offer(false, new Item[]{new Item(ItemID.COINS, 100), new Item(4151, 1)});
		capture.confirm();
		Ledger.Trade trade = capture.complete(List.of(), 1);
		assertTrue(trade.otherItems);
		assertEquals(100, trade.received);
	}

	@Test
	public void beginningAnotherTradeDiscardsThePreviousOffer()
	{
		TradeCapture capture = start();
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		capture.confirm();
		capture.begin(LedgerTest.configured(), "My main", 301);
		capture.opponent("Other buyer");
		capture.offer(false, new Item[]{new Item(ItemID.COINS, 500)});
		capture.confirm();
		Ledger.Trade trade = capture.complete(List.of(), 1);
		assertEquals(0, trade.given);
		assertEquals(500, trade.received);
		assertEquals("Other buyer", trade.counterparty);
	}

	@Test
	public void closingFirstScreenAfterConfirmationOpensDoesNotExpireTrade()
	{
		TradeCapture capture = start();
		capture.offer(true, new Item[]{new Item(ItemID.COINS, 100)});
		capture.confirm();
		capture.closed(false, 10);
		capture.tick(100);
		assertEquals(100, capture.complete(List.of(), 1).given);
	}
}
