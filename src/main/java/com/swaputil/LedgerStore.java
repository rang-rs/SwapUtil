package com.swaputil;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.runelite.client.util.Filepath;

final class LedgerStore
{
	private static final Object PROCESS_LOCK = new Object();
	private final Filepath directory;
	private final Gson gson;
	private Ledger cached;
	private long cachedSize;
	private FileTime cachedModified;

	LedgerStore(Filepath directory, Gson gson) { this.directory = directory; this.gson = gson; }
	Ledger read() throws IOException { return access(null); }
	Ledger update(Consumer<Ledger> change) throws IOException { return access(change); }

	private Ledger access(Consumer<Ledger> change) throws IOException
	{
		synchronized (PROCESS_LOCK)
		{
			directory.createDirectories();
			try (FileChannel channel = directory.join("ledger.lock").openFileChannel(
				StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = channel.lock())
			{
				Filepath file = directory.join("ledger.json");
				boolean exists = file.exists();
				if (change == null && cached != null && exists
					&& file.size() == cachedSize && file.getLastModifiedTime().equals(cachedModified))
				{
					return cached;
				}
				Ledger ledger;
				if (exists)
				{
					try (Reader reader = file.openReader()) { ledger = decode(reader); }
				}
				else { ledger = new Ledger(); }
				if (change != null) { change.accept(ledger); ledger.revision++; }
				if (!exists || change != null) { save(file, ledger); }
				cachedSize = file.size();
				cachedModified = file.getLastModifiedTime();
				cached = ledger;
				return ledger;
			}
		}
	}

	private Ledger decode(Reader json) throws IOException
	{
		try
		{
			JsonObject root = new JsonParser().parse(json).getAsJsonObject();
			for (String field : new String[]{"version", "revision", "group", "rates", "accountModes", "settlements", "trades"})
			{
				if (!root.has(field)) { throw new IllegalArgumentException("Missing ledger field: " + field); }
			}
			Ledger ledger = gson.fromJson(root, Ledger.class);
			if (ledger == null || ledger.version != 1 || ledger.group == null || ledger.accountModes == null
				|| ledger.rates == null || ledger.rates.isEmpty() || ledger.settlements == null
				|| ledger.settlements.isEmpty() || ledger.trades == null)
			{
				throw new IllegalArgumentException("Unsupported or incomplete ledger");
			}
			Group.parse(ledger.group);
			for (Integer mode : ledger.accountModes.values())
			{
				if (mode == null || mode < 0 || mode > 1) { throw new IllegalArgumentException("Invalid account mode"); }
			}
			if (ledger.current().closedAt != 0) { throw new IllegalArgumentException("No open settlement"); }
			Set<String> rates = new HashSet<>();
			for (Ledger.RatePeriod rate : ledger.rates)
			{
				new Ledger.RatePeriod(rate.modeA, rate.modeB, rate.buy, rate.sell);
				if (rate.id == null || !rates.add(rate.id)) { throw new IllegalArgumentException("Duplicate rate period"); }
			}
			Set<String> settlements = new HashSet<>();
			for (Ledger.Settlement settlement : ledger.settlements)
			{
				if (settlement.id == null || !settlements.add(settlement.id))
				{
					throw new IllegalArgumentException("Duplicate settlement");
				}
			}
			Set<String> ids = new HashSet<>();
			for (Ledger.Trade trade : ledger.trades)
			{
				if (trade == null || trade.id == null || !ids.add(trade.id) || trade.given < 0 || trade.received < 0
					|| trade.mode < 0 || trade.mode > 1 || !rates.contains(trade.ratePeriod)
					|| !settlements.contains(trade.settlement) || trade.participants == null
					|| !trade.participants.contains(trade.operator) || trade.participants.contains(null)
					|| new HashSet<>(trade.participants).size() != trade.participants.size()
					|| trade.account == null || trade.counterparty == null)
				{
					throw new IllegalArgumentException("Incomplete trade record");
				}
			}
			return ledger;
		}
		catch (JsonParseException | IllegalArgumentException | IllegalStateException | NullPointerException ex)
		{
			throw new IOException("Cannot read ledger.json. Existing records have been left untouched.", ex);
		}
	}

	private void save(Filepath file, Ledger ledger) throws IOException
	{
		Filepath temporary = directory.createTempFile("ledger-", ".tmp");
		try
		{
			byte[] bytes = gson.toJson(ledger).getBytes(StandardCharsets.UTF_8);
			try (FileChannel out = temporary.openFileChannel(StandardOpenOption.WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) { out.write(buffer); }
				out.force(true);
			}
			if (file.exists())
			{
				file.copyTo(directory.join("ledger.backup.json"), StandardCopyOption.REPLACE_EXISTING);
			}
			temporary.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally { temporary.deleteIfExists(); }
	}

	Filepath export() throws IOException
	{
		Ledger ledger = read();
		Filepath file = directory.createTempFile("swaputil-export-", ".json");
		file.write(gson.toJson(ledger));
		return file;
	}
}
