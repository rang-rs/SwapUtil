package com.swaputil;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LedgerStoreTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	private static LedgerStore store(Path path)
	{
		return new LedgerStore(Filepath.Unchecked.getRooted(path), new Gson());
	}

	private static Ledger exported(LedgerStore store) throws IOException
	{
		try (Reader reader = store.export().openReader())
		{
			return new Gson().fromJson(reader, Ledger.class);
		}
	}

	@Test
	public void cachedReaderSeesOtherClientsChangesAndExportsThem() throws Exception
	{
		Path path = temporary.newFolder().toPath();
		LedgerStore reader = store(path);
		LedgerStore writer = store(path);
		assertEquals("900", reader.read().rates().buy);
		writer.update(ledger -> ledger.configure(null, null, null, "950", null));
		assertEquals("950", reader.read().rates().buy);
		writer.update(ledger -> LedgerTest.trade(ledger, "Me", 0, 0, 100, "Me"));
		assertEquals(1, reader.read().trades.size());
		Ledger exported = exported(reader);
		assertEquals(1, exported.trades.size());
		assertEquals("950", exported.rates().buy);
	}

	@Test
	public void independentRateWritesMergeUnderTheFileLock() throws Exception
	{
		Path path = temporary.newFolder().toPath();
		LedgerStore first = store(path);
		LedgerStore second = store(path);
		first.read();
		second.read();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try
		{
			Future<Ledger> buy = executor.submit(() -> first.update(ledger -> ledger.configure(null, null, null, "1000", null)));
			Future<Ledger> sell = executor.submit(() -> second.update(ledger -> ledger.configure(null, null, null, null, "1100")));
			buy.get();
			sell.get();
		}
		finally { executor.shutdown(); }
		Ledger result = first.read();
		assertEquals("1000", result.rates().buy);
		assertEquals("1100", result.rates().sell);
		assertEquals(3, result.rates.size());
		assertEquals("900", result.rates.get(0).buy);
		assertEquals("1500", result.rates.get(0).sell);
	}

	@Test
	public void twoClientsShareRatesAndDoNotLoseEachOthersWrites() throws Exception
	{
		Path path = temporary.newFolder().toPath();
		LedgerStore first = store(path);
		LedgerStore second = store(path);
		first.update(ledger -> ledger.group = LedgerTest.configured().group);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try
		{
			Future<?> a = executor.submit(() -> append(first));
			Future<?> b = executor.submit(() -> append(second));
			a.get();
			b.get();
		}
		finally { executor.shutdown(); }
		assertEquals(40, first.read().trades.size());
		assertEquals(40, second.read().trades.size());
		assertTrue(Files.exists(path.resolve("ledger.backup.json")));
		assertEquals(41, first.read().revision);
	}

	private void append(LedgerStore store)
	{
		for (int i = 0; i < 20; i++)
		{
			try { store.update(ledger -> LedgerTest.trade(ledger, "Me", 0, 0, 100, "Alex", "Me")); }
			catch (IOException ex) { throw new RuntimeException(ex); }
		}
	}

	@Test
	public void failedMutationDoesNotOverwriteRecords() throws Exception
	{
		Path path = temporary.newFolder().toPath();
		LedgerStore store = store(path);
		store.read();
		String before = Files.readString(path.resolve("ledger.json"));
		try
		{
			store.update(ledger -> { ledger.group = "bad"; throw new IllegalArgumentException("invalid"); });
			fail();
		}
		catch (IllegalArgumentException expected) { }
		assertEquals(before, Files.readString(path.resolve("ledger.json")));
		assertEquals("", store.read().group);
	}

	@Test
	public void corruptFileIsNeverReplacedWithAnEmptyLedger() throws Exception
	{
		Path path = temporary.newFolder().toPath();
		LedgerStore store = store(path);
		store.read();
		Files.writeString(path.resolve("ledger.json"), "broken");
		try { store.read(); fail(); }
		catch (IOException expected) { assertTrue(expected.getMessage().contains("untouched")); }
		assertEquals("broken", Files.readString(path.resolve("ledger.json")));
	}

	@Test
	public void incompleteJsonCannotSilentlyResetTheLedger() throws Exception
	{
		Path path = temporary.newFolder().toPath();
		LedgerStore store = store(path);
		Files.writeString(path.resolve("ledger.json"), "{}");
		try { store.read(); fail(); }
		catch (IOException expected) { assertTrue(expected.getMessage().contains("untouched")); }
		assertEquals("{}", Files.readString(path.resolve("ledger.json")));
	}

	@Test
	public void exportIncludesArchivedTradesWithoutResettingAnything() throws Exception
	{
		LedgerStore store = store(temporary.newFolder().toPath());
		store.update(ledger -> LedgerTest.trade(ledger, "Me", 0, 0, 100, "Me"));
		store.update(ledger -> ledger.settle(ledger.revision));
		Ledger exported = exported(store);
		assertEquals(1, exported.trades.size());
		assertEquals(2, exported.settlements.size());
		assertEquals(store.read().revision, exported.revision);
	}
}
