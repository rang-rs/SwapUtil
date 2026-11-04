package com.swaputil;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "SwapUtil",
	internalName = "swaputil",
	legacyDataDirectory = "swaputil",
	description = "Tracks currency swaps, partner shares and settlements",
	tags = {"trade", "swap", "gp", "tracker"}
)
public class SwapUtilPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(SwapUtilPlugin.class);
	private static final int OTHER_OFFER = InventoryID.TRADEOFFER | 0x8000;
	@Inject private Client client;
	@Inject private ClientToolbar toolbar;
	@Inject private Gson gson;
	private final TradeCapture capture = new TradeCapture();
	private final Queue<Ledger.Trade> pending = new ConcurrentLinkedQueue<>();
	private volatile Ledger ledger;
	private volatile String account = "";
	private volatile boolean running;
	private LedgerStore store;
	private ScheduledExecutorService worker;
	private SwapUtilPanel panel;
	private NavigationButton navigation;
	private String storageError = "";

	@Override
	protected void startUp() throws IOException
	{
		store = new LedgerStore(getPluginDirectory(), gson);
		running = true;
		worker = Executors.newSingleThreadScheduledExecutor(task ->
		{
			Thread thread = new Thread(task, "swaputil-storage");
			thread.setDaemon(true);
			return thread;
		});
		SwingUtilities.invokeLater(() ->
		{
			if (!running) { return; }
			panel = new SwapUtilPanel(this::update, this::export);
			navigation = NavigationButton.builder().tooltip("SwapUtil")
				.icon(ImageUtil.loadImageResource(getClass(), "icon.png")).panel(panel).priority(6).build();
			toolbar.addNavigation(navigation);
		});
		worker.scheduleWithFixedDelay(this::refresh, 0, 1, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		running = false;
		capture.clear();
		account = "";
		if (worker != null) { worker.execute(this::flush); worker.shutdown(); }
		SwingUtilities.invokeLater(() ->
		{
			if (navigation != null) { toolbar.removeNavigation(navigation); }
			panel = null;
		});
	}

	private void flush()
	{
		try
		{
			Ledger.Trade trade;
			while ((trade = pending.peek()) != null)
			{
				Ledger.Trade next = trade;
				ledger = store.update(data -> data.add(next));
				pending.remove(next);
			}
		}
		catch (IOException | RuntimeException ex) { error(ex); }
	}

	private void refresh()
	{
		if (!running) { return; }
		flush();
		try
		{
			ledger = store.read();
			if (pending.isEmpty()) { storageError = ""; }
		}
		catch (IOException | RuntimeException ex) { error(ex); }
		Ledger snapshot = ledger;
		String currentAccount = account;
		String error = storageError;
		SwingUtilities.invokeLater(() ->
		{
			if (running && panel != null) { panel.render(snapshot, currentAccount, error); }
		});
	}

	private void error(Exception ex)
	{
		String message = "Records could not be saved or loaded. " + (pending.isEmpty() ? "" : pending.size() + " trade(s) waiting. ")
			+ "Check the SwapUtil folder and client log.";
		if (!message.equals(storageError)) { log.warn("SwapUtil storage failure", ex); }
		storageError = message;
	}

	private void update(Consumer<Ledger> change)
	{
		worker.execute(() ->
		{
			flush();
			try
			{
				if (!pending.isEmpty()) { throw new IllegalStateException("Wait for pending trades to save before making changes."); }
				ledger = store.update(change);
				SwingUtilities.invokeLater(() -> { if (panel != null) { panel.saved(); } });
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Could not update SwapUtil records", ex);
				SwingUtilities.invokeLater(() -> { if (panel != null) { panel.failure(ex.getMessage()); } });
			}
			refresh();
		});
	}

	private void export()
	{
		worker.execute(() ->
		{
			try
			{
				flush();
				if (!pending.isEmpty()) { throw new IOException("Some trades have not been saved yet."); }
				String path = store.export().toString();
				SwingUtilities.invokeLater(() -> { if (panel != null) { panel.exported(path); } });
			}
			catch (IOException ex)
			{
				SwingUtilities.invokeLater(() -> { if (panel != null) { panel.failure(ex.getMessage()); } });
			}
		});
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		if (worker != null && !worker.isShutdown()) { event.waitFor(worker.submit(this::flush)); }
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player player = client.getLocalPlayer();
		account = player == null ? "" : player.getName();
		capture.tick(client.getTickCount());
		readOpponent();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING
			|| event.getGameState() == GameState.CONNECTION_LOST)
		{
			capture.clear();
			account = "";
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (!running) { return; }
		if (event.getGroupId() == InterfaceID.TRADEMAIN)
		{
			Player player = client.getLocalPlayer();
			if (player == null) { return; }
			capture.begin(ledger, player.getName(), client.getWorld());
			readOffers();
			readOpponent();
		}
		else if (event.getGroupId() == InterfaceID.TRADECONFIRM)
		{
			readOffers();
			readOpponent();
			capture.confirm();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.TRADEMAIN || event.getGroupId() == InterfaceID.TRADECONFIRM)
		{
			capture.closed(event.getGroupId() == InterfaceID.TRADECONFIRM, client.getTickCount());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.TRADEOFFER || event.getContainerId() == OTHER_OFFER)
		{
			capture.offer(event.getContainerId() == InventoryID.TRADEOFFER, event.getItemContainer().getItems());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!running || event.getType() != ChatMessageType.TRADE) { return; }
		if ("Accepted trade.".equals(event.getMessage()))
		{
			Ledger.Trade trade = capture.complete(onlineMembers(), System.currentTimeMillis());
			if (trade != null)
			{
				pending.add(trade);
				worker.execute(this::refresh);
			}
		}
		else if ("Other player declined trade.".equals(event.getMessage())) { capture.clear(); }
	}

	private void readOffers()
	{
		ItemContainer given = client.getItemContainer(InventoryID.TRADEOFFER);
		ItemContainer received = client.getItemContainer(OTHER_OFFER);
		if (given != null) { capture.offer(true, given.getItems()); }
		if (received != null) { capture.offer(false, received.getItems()); }
	}

	private void readOpponent()
	{
		Widget widget = client.getWidget(InterfaceID.Trademain.TITLE);
		if (widget == null || widget.isHidden()) { widget = client.getWidget(InterfaceID.Tradeconfirm.TRADEOPPONENT); }
		if (widget == null || widget.isHidden()) { return; }
		String text = Text.removeTags(widget.getText()).replace('\u00a0', ' ').trim();
		String name = text.replaceFirst("(?i)^Trading with:\\s*", "");
		if (!name.equals(text)) { capture.opponent(name); }
	}

	private List<String> onlineMembers()
	{
		List<String> names = new ArrayList<>();
		FriendsChatManager chat = client.getFriendsChatManager();
		if (chat != null)
		{
			for (FriendsChatMember member : chat.getMembers())
			{
				if (member.getWorld() > 0) { names.add(member.getName()); }
			}
		}
		return names;
	}
}
