package com.swaputil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Ledger
{
	int version = 1;
	long revision;
	String group = "";
	Map<String, Integer> accountModes = new LinkedHashMap<>();
	List<RatePeriod> rates = new ArrayList<>();
	List<Settlement> settlements = new ArrayList<>();
	List<Trade> trades = new ArrayList<>();

	Ledger()
	{
		rates.add(new RatePeriod("OSRS", "Hardcore", "900", "1500"));
		settlements.add(new Settlement());
	}

	RatePeriod rates() { return rates.get(rates.size() - 1); }
	Settlement current() { return settlements.get(settlements.size() - 1); }

	void configure(String groupText, String modeA, String modeB, String buy, String sell)
	{
		String nextGroup = groupText == null ? group : groupText.trim();
		Group.parse(nextGroup);
		RatePeriod current = rates();
		RatePeriod next = new RatePeriod(
			modeA == null ? current.modeA : modeA,
			modeB == null ? current.modeB : modeB,
			buy == null ? current.buy : buy,
			sell == null ? current.sell : sell);
		group = nextGroup;
		if (!rates().sameValues(next)) { rates.add(next); }
	}

	void setMode(String account, int mode)
	{
		if (Group.normalize(account).isEmpty() || mode < -1 || mode > 1)
		{
			throw new IllegalArgumentException("Select an account and mode.");
		}
		if (mode < 0) { accountModes.remove(Group.normalize(account)); }
		else { accountModes.put(Group.normalize(account), mode); }
	}

	void add(Trade trade)
	{
		if (trades.stream().anyMatch(t -> t.id.equals(trade.id))) { return; }
		if (trade.given < 0 || trade.received < 0 || trade.mode < 0 || trade.mode > 1
			|| trade.participants.isEmpty() || !trade.participants.contains(trade.operator)
			|| rates.stream().noneMatch(r -> r.id.equals(trade.ratePeriod)))
		{
			throw new IllegalArgumentException("Incomplete trade record.");
		}
		trade.settlement = current().id;
		trade.carriedForward = settlements.size() > 1 && trade.time < current().startedAt;
		trades.add(trade);
	}

	void settle(long expectedRevision)
	{
		checkRevision(expectedRevision);
		current().closedAt = System.currentTimeMillis();
		settlements.add(new Settlement());
	}

	void checkRevision(long expected)
	{
		if (revision != expected)
		{
			throw new IllegalStateException("Records changed in another client. Review the latest totals and try again.");
		}
	}

	static final class RatePeriod
	{
		String id = UUID.randomUUID().toString();
		long startedAt = System.currentTimeMillis();
		String modeA;
		String modeB;
		String buy;
		String sell;

		RatePeriod(String modeA, String modeB, String buy, String sell)
		{
			this.modeA = modeA.trim();
			this.modeB = modeB.trim();
			if (this.modeA.isEmpty() || this.modeB.isEmpty() || this.modeA.equalsIgnoreCase(this.modeB)
				|| this.modeA.length() > 24 || this.modeB.length() > 24)
			{
				throw new IllegalArgumentException("Enter two distinct mode names, up to 24 characters each.");
			}
			this.buy = rate(buy);
			this.sell = rate(sell);
		}

		private static String rate(String value)
		{
			try
			{
				BigDecimal rate = new BigDecimal(value.trim());
				if (rate.signum() <= 0 || rate.compareTo(new BigDecimal("1000000000000")) > 0 || rate.scale() > 8)
				{
					throw new NumberFormatException();
				}
				return rate.stripTrailingZeros().toPlainString();
			}
			catch (NumberFormatException ex)
			{
				throw new IllegalArgumentException("Rates must be positive numbers with up to 8 decimal places.");
			}
		}

		boolean sameValues(RatePeriod other)
		{
			return modeA.equals(other.modeA) && modeB.equals(other.modeB)
				&& buy.equals(other.buy) && sell.equals(other.sell);
		}

		String label(int mode) { return mode == 0 ? modeA : modeB; }
	}

	static final class Settlement
	{
		String id = UUID.randomUUID().toString();
		long startedAt = System.currentTimeMillis();
		long closedAt;
	}

	static final class Trade
	{
		String id = UUID.randomUUID().toString();
		long time = System.currentTimeMillis();
		String settlement;
		String ratePeriod;
		String account;
		String operator;
		String counterparty;
		int world;
		int mode;
		long given;
		long received;
		List<String> participants = new ArrayList<>();
		boolean partnerTransfer;
		boolean otherItems;
		boolean carriedForward;
	}
}
