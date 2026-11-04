package com.swaputil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class Balances
{
	static List<Payment> payments(Ledger ledger, String settlement)
	{
		Map<List<String>, Payment> payments = new LinkedHashMap<>();
		for (Ledger.Trade trade : ledger.trades)
		{
			if (!settlement.equals(trade.settlement) || trade.partnerTransfer) { continue; }
			Fraction share = new Fraction(trade.received - trade.given, trade.participants.size());
			String operatorKey = Group.normalize(trade.operator);
			for (String partner : trade.participants)
			{
				String partnerKey = Group.normalize(partner);
				if (partnerKey.equals(operatorKey)) { continue; }
				boolean forward = operatorKey.compareTo(partnerKey) < 0;
				String first = forward ? trade.operator : partner;
				String second = forward ? partner : trade.operator;
				List<String> key = List.of(Group.normalize(first), Group.normalize(second), Integer.toString(trade.mode));
				Payment payment = payments.computeIfAbsent(key, ignored -> new Payment(first, second, trade.mode));
				payment.amount = payment.amount.add(forward ? share : share.negate());
			}
		}
		return new ArrayList<>(payments.values());
	}

	static List<Bucket> buckets(Ledger ledger, String settlement)
	{
		Map<List<String>, Bucket> buckets = new LinkedHashMap<>();
		for (Ledger.Trade trade : ledger.trades)
		{
			if (!settlement.equals(trade.settlement) || trade.partnerTransfer) { continue; }
			List<String> key = trade.participants.stream().map(Group::normalize).sorted().collect(Collectors.toList());
			key.add(0, trade.ratePeriod);
			Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(trade.ratePeriod, trade.participants));
			bucket.given[trade.mode] = Math.addExact(bucket.given[trade.mode], trade.given);
			bucket.received[trade.mode] = Math.addExact(bucket.received[trade.mode], trade.received);
			bucket.count++;
		}
		return new ArrayList<>(buckets.values());
	}

	static final class Payment
	{
		final String first;
		final String second;
		final int mode;
		Fraction amount = Fraction.ZERO;
		Payment(String first, String second, int mode) { this.first = first; this.second = second; this.mode = mode; }
		String payer() { return amount.signum() >= 0 ? first : second; }
		String payee() { return amount.signum() >= 0 ? second : first; }
	}

	static final class Bucket
	{
		final String ratePeriod;
		final List<String> participants;
		final long[] received = new long[2];
		final long[] given = new long[2];
		int count;
		Bucket(String ratePeriod, List<String> participants)
		{
			this.ratePeriod = ratePeriod;
			this.participants = List.copyOf(participants);
		}
	}
}
