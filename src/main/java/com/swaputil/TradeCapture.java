package com.swaputil;

import java.util.Collection;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;

final class TradeCapture
{
	private Ledger.Trade trade;
	private Group group;
	private boolean confirmation;
	private int closedAt = -1;
	private boolean givenItems;
	private boolean receivedItems;

	void begin(Ledger ledger, String account, int world)
	{
		clear();
		if (ledger == null) { return; }
		group = Group.parse(ledger.group);
		String operator = group.person(account);
		Integer mode = ledger.accountModes.get(Group.normalize(account));
		if (operator == null || mode == null) { return; }
		trade = new Ledger.Trade();
		trade.account = account;
		trade.operator = operator;
		trade.world = world;
		trade.mode = mode;
		trade.ratePeriod = ledger.rates().id;
	}

	void opponent(String name)
	{
		if (trade != null && !name.isEmpty()) { trade.counterparty = name; }
	}

	void offer(boolean given, Item[] items)
	{
		if (trade == null || confirmation || closedAt >= 0) { return; }
		long gp = 0;
		boolean other = false;
		for (Item item : items)
		{
			if (item == null || item.getId() < 0 || item.getQuantity() <= 0) { continue; }
			if (item.getId() == ItemID.COINS) { gp += item.getQuantity(); }
			else if (item.getId() == ItemID.PLATINUM) { gp += item.getQuantity() * 1000L; }
			else { other = true; }
		}
		if (given) { trade.given = gp; givenItems = other; }
		else { trade.received = gp; receivedItems = other; }
	}

	void confirm()
	{
		confirmation = true;
		closedAt = -1;
	}

	void closed(boolean confirmationScreen, int tick)
	{
		if (confirmationScreen == confirmation) { closedAt = tick; }
	}
	void tick(int tick) { if (closedAt >= 0 && tick - closedAt > 2) { clear(); } }

	Ledger.Trade complete(Collection<String> online, long time)
	{
		Ledger.Trade result = trade;
		if (result == null || !confirmation || result.counterparty == null
			|| (result.given == 0 && result.received == 0))
		{
			clear();
			return null;
		}
		result.time = time;
		result.participants = group.participants(result.account, online);
		result.partnerTransfer = group.person(result.counterparty) != null;
		result.otherItems = givenItems || receivedItems;
		clear();
		return result;
	}

	void clear()
	{
		trade = null;
		group = null;
		confirmation = false;
		closedAt = -1;
		givenItems = false;
		receivedItems = false;
	}
}
