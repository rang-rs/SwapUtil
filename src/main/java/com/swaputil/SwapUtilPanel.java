package com.swaputil;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

final class SwapUtilPanel extends PluginPanel
{
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault());
	private final Consumer<Consumer<Ledger>> update;
	private final JTextArea status = text();
	private final JTextArea error = text();
	private final JComboBox<String> mode = new JComboBox<>();
	private final JTextArea summary = text();
	private final JTextArea payments = text();
	private final JTextArea buckets = text();
	private final JTextArea history = text();
	private final JComboBox<String> periods = new JComboBox<>();
	private final List<String> periodIds = new ArrayList<>();
	private final JTextField modeA = new JTextField();
	private final JTextField modeB = new JTextField();
	private final JTextField buy = new JTextField();
	private final JTextField sell = new JTextField();
	private final JComboBox<String> rateDirection = new JComboBox<>();
	private final JPanel rateFields = new JPanel(new CardLayout());
	private final JLabel rateUnit = new JLabel("Rate");
	private final Set<JTextComponent> edited = new HashSet<>();
	private final JTextArea group = new JTextArea(5, 16);
	private final JButton apply = new JButton("Save settings");
	private final JButton settle = new JButton("Mark settled");
	private Ledger ledger;
	private String account = "";
	private boolean rendering;
	private boolean busy;
	private boolean settingsSaving;
	private long shownRevision = -1;
	private String historySelection;
	private String notice = "";

	SwapUtilPanel(Consumer<Consumer<Ledger>> update, Runnable export)
	{
		this.update = update;
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel top = column();
		addLine(top, status);
		addLine(top, mode);
		error.setForeground(new Color(255, 180, 110));
		addLine(top, error);
		add(top, BorderLayout.NORTH);

		JTabbedPane tabs = new JTabbedPane();
		JPanel overview = column();
		addLine(overview, summary);
		tabs.addTab("Totals", overview);

		JPanel setup = column();
		field(setup, "Mode A", modeA);
		field(setup, "Mode B", modeB);
		addLine(setup, new JLabel("Rate direction"));
		addLine(setup, rateDirection);
		addLine(setup, rateUnit);
		rateFields.add(rateRow(buy, true), "buy");
		rateFields.add(rateRow(sell, false), "sell");
		addLine(setup, rateFields);
		rateFields.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		addLine(setup, new JLabel("Group"));
		addLine(setup, note("Include yourself and your partners, one person per line.\nFor multiple accounts: Rang = hc rang, rang swaps"));
		group.setLineWrap(true);
		group.setWrapStyleWord(true);
		JScrollPane groupScroll = new JScrollPane(group);
		groupScroll.setPreferredSize(new Dimension(200, 100));
		addLine(setup, groupScroll);
		addLine(setup, apply);
		tabs.addTab("Setup", setup);

		JPanel records = column();
		addLine(records, periods);
		JButton exportButton = new JButton("Export records");
		addLine(records, exportButton);
		addLine(records, history);
		tabs.addTab("Log", records);

		JPanel splits = column();
		addLine(splits, new JLabel("To settle"));
		addLine(splits, payments);
		addLine(splits, settle);
		addLine(splits, new JLabel("Rate and attendance buckets"));
		addLine(splits, buckets);
		tabs.addTab("Split", splits);
		add(tabs, BorderLayout.CENTER);

		mode.addActionListener(event ->
		{
			if (rendering || ledger == null || account.isEmpty() || busy) { return; }
			String selectedAccount = account;
			int selectedMode = mode.getSelectedIndex() - 1;
			submit(data -> data.setMode(selectedAccount, selectedMode));
		});
		apply.addActionListener(event -> saveSettings());
		rateDirection.addActionListener(event ->
		{
			((CardLayout) rateFields.getLayout()).show(rateFields, rateDirection.getSelectedIndex() == 0 ? "buy" : "sell");
			rateUnit.setLabelFor(rateDirection.getSelectedIndex() == 0 ? buy : sell);
		});
		settle.addActionListener(event -> markSettled());
		exportButton.addActionListener(event -> export.run());
		periods.addActionListener(event ->
		{
			if (!rendering && periods.getSelectedIndex() >= 0)
			{
				historySelection = periodIds.get(periods.getSelectedIndex());
				renderHistory();
			}
		});
		for (JTextComponent field : List.of(modeA, modeB, buy, sell, group))
		{
			field.getDocument().addDocumentListener(new DocumentListener()
			{
				public void insertUpdate(DocumentEvent e) { changed(); }
				public void removeUpdate(DocumentEvent e) { changed(); }
				public void changedUpdate(DocumentEvent e) { changed(); }
				private void changed()
				{
					if (!rendering) { edited.add(field); }
				}
			});
		}
		status.setText("Loading local records...");
		mode.setEnabled(false);
		apply.setEnabled(false);
		settle.setEnabled(false);
	}

	void render(Ledger data, String currentAccount, String storageError)
	{
		ledger = data;
		account = currentAccount == null ? "" : currentAccount;
		setTextIfChanged(error, storageError.isEmpty() ? notice : storageError);
		error.setVisible(!error.getText().isEmpty());
		if (data == null) { return; }
		rendering = true;
		try
		{
			Ledger.RatePeriod rates = data.rates();
			Group people = Group.parse(data.group);
			Integer selectedMode = data.accountModes.get(Group.normalize(account));
			String owner = people.person(account);
			if (account.isEmpty()) { setTextIfChanged(status, "Log in to select this account's mode."); }
			else if (owner == null) { setTextIfChanged(status, "Add this account under Setup to start tracking."); }
			else if (selectedMode == null) { setTextIfChanged(status, "Select a mode to start tracking."); }
			else { setTextIfChanged(status, ""); }
			status.setVisible(!status.getText().isEmpty());
			String[] modes = {"Paused / select mode", rates.modeA, rates.modeB};
			if (mode.getItemCount() != 3 || !rates.modeA.equals(mode.getItemAt(1)) || !rates.modeB.equals(mode.getItemAt(2)))
			{
				mode.removeAllItems();
				for (String label : modes) { mode.addItem(label); }
			}
			mode.setSelectedIndex(selectedMode == null ? 0 : selectedMode + 1);
			mode.setEnabled(!account.isEmpty() && !busy && storageError.isEmpty());
			apply.setEnabled(!busy && storageError.isEmpty());
			settle.setEnabled(!busy && storageError.isEmpty() && data.trades.stream().anyMatch(t -> t.settlement.equals(data.current().id) && !t.partnerTransfer));
			syncField(modeA, rates.modeA);
			syncField(modeB, rates.modeB);
			syncField(buy, rates.buy);
			syncField(sell, rates.sell);
			syncField(group, data.group);
			String buyDirection = rates.modeB + " \u2192 " + rates.modeA;
			String sellDirection = rates.modeA + " \u2192 " + rates.modeB;
			if (rateDirection.getItemCount() != 2 || !buyDirection.equals(rateDirection.getItemAt(0))
				|| !sellDirection.equals(rateDirection.getItemAt(1)))
			{
				int selected = Math.max(0, rateDirection.getSelectedIndex());
				rateDirection.removeAllItems();
				rateDirection.addItem(buyDirection);
				rateDirection.addItem(sellDirection);
				rateDirection.setSelectedIndex(selected);
			}
			if (shownRevision != data.revision)
			{
				setTextIfChanged(summary, totals(data.current().id));
				setTextIfChanged(payments, paymentText(data.current().id));
				setTextIfChanged(buckets, bucketText(data.current().id));
				periodIds.clear();
				periods.removeAllItems();
				for (int i = data.settlements.size() - 1; i >= 0; i--)
				{
					Ledger.Settlement settlement = data.settlements.get(i);
					periodIds.add(settlement.id);
					periods.addItem((settlement.closedAt == 0 ? "Current: " : "Settled: ") + date(settlement.startedAt));
				}
				int selected = periodIds.indexOf(historySelection);
				periods.setSelectedIndex(Math.max(0, selected));
				historySelection = periodIds.get(periods.getSelectedIndex());
				renderHistory();
				shownRevision = data.revision;
			}
		}
		finally { rendering = false; }
	}

	private void saveSettings()
	{
		if (ledger == null || busy) { return; }
		String names = change(group);
		String a = change(modeA);
		String b = change(modeB);
		String buyValue = change(buy);
		String sellValue = change(sell);
		if (edited.isEmpty()) { return; }
		try
		{
			Group.parse(names == null ? ledger.group : names);
			Ledger.RatePeriod rates = ledger.rates();
			new Ledger.RatePeriod(a == null ? rates.modeA : a, b == null ? rates.modeB : b,
				buyValue == null ? rates.buy : buyValue, sellValue == null ? rates.sell : sellValue);
		}
		catch (IllegalArgumentException ex) { failure(ex.getMessage()); return; }
		settingsSaving = true;
		submit(data -> data.configure(names, a, b, buyValue, sellValue));
	}

	private String change(JTextComponent field) { return edited.contains(field) ? field.getText() : null; }

	private void syncField(JTextComponent field, String value)
	{
		if (!edited.contains(field)) { setTextIfChanged(field, value); }
	}

	private void enableSettings(boolean enabled)
	{
		for (JTextComponent field : List.of(modeA, modeB, buy, sell, group)) { field.setEditable(enabled); }
		rateDirection.setEnabled(enabled);
	}

	private void submit(Consumer<Ledger> action)
	{
		busy = true;
		enableSettings(false);
		apply.setEnabled(false);
		settle.setEnabled(false);
		mode.setEnabled(false);
		update.accept(action);
	}

	private void markSettled()
	{
		if (ledger == null || busy) { return; }
		long revision = ledger.revision;
		String message = paymentText(ledger.current().id) + "\n\nMark these payments as settled?";
		String[] options = {"Mark settled", "Cancel"};
		if (JOptionPane.showOptionDialog(this, message, "Mark settled", JOptionPane.DEFAULT_OPTION,
			JOptionPane.PLAIN_MESSAGE, null, options, options[1]) == 0)
		{
			submit(data -> data.settle(revision));
			historySelection = null;
		}
	}

	private String totals(String settlement)
	{
		long[] received = new long[2];
		long[] given = new long[2];
		int[] counts = new int[2];
		for (Ledger.Trade trade : ledger.trades)
		{
			if (!settlement.equals(trade.settlement) || trade.partnerTransfer) { continue; }
			received[trade.mode] = Math.addExact(received[trade.mode], trade.received);
			given[trade.mode] = Math.addExact(given[trade.mode], trade.given);
			counts[trade.mode]++;
		}
		StringBuilder result = new StringBuilder();
		if (ledger.trades.stream().anyMatch(t -> settlement.equals(t.settlement) && t.carriedForward && !t.partnerTransfer))
		{
			result.append("Includes a trade saved after the previous settlement.\n");
		}
		for (int mode = 0; mode < 2; mode++)
		{
			result.append('\n').append(ledger.rates().label(mode)).append("\nTrades: ").append(gp(counts[mode]))
				.append("\nReceived: ").append(gp(received[mode]))
				.append("\nGiven: ").append(gp(given[mode]))
				.append("\nNet: ").append(gp(received[mode] - given[mode])).append('\n');
		}
		return result.toString().trim();
	}

	private String paymentText(String settlement)
	{
		List<String> lines = new ArrayList<>();
		for (Balances.Payment payment : Balances.payments(ledger, settlement))
		{
			BigDecimal amount = payment.amount.rounded().abs();
			if (amount.signum() != 0)
			{
				lines.add(payment.payer() + " pays " + payment.payee() + "\n" + gp(amount) + " " + ledger.rates().label(payment.mode));
			}
		}
		return lines.isEmpty() ? "Nothing to settle." : String.join("\n\n", lines);
	}

	private String bucketText(String settlement)
	{
		List<String> lines = new ArrayList<>();
		for (Balances.Bucket bucket : Balances.buckets(ledger, settlement))
		{
			Ledger.RatePeriod rate = ledger.rates.stream().filter(r -> r.id.equals(bucket.ratePeriod)).findFirst().orElseThrow();
			StringBuilder text = new StringBuilder(date(rate.startedAt) + "\nRates " + rate.buy + " / " + rate.sell
				+ "\n" + String.join(", ", bucket.participants) + "\n" + bucket.count + " trades");
			for (int mode = 0; mode < 2; mode++)
			{
				text.append('\n').append(rate.label(mode)).append(": +").append(gp(bucket.received[mode]))
					.append(" / -").append(gp(bucket.given[mode]));
			}
			lines.add(text.toString());
		}
		return lines.isEmpty() ? "No customer trades yet." : String.join("\n\n", lines);
	}

	private void renderHistory()
	{
		if (ledger == null || historySelection == null) { return; }
		setTextIfChanged(history, totals(historySelection) + "\n\n" + paymentText(historySelection)
			+ "\n\n" + bucketText(historySelection));
	}

	void saved()
	{
		busy = false;
		enableSettings(true);
		if (settingsSaving) { edited.clear(); settingsSaving = false; }
		notice = "";
	}

	void failure(String message)
	{
		busy = false;
		enableSettings(true);
		settingsSaving = false;
		notice = message == null ? "Unable to save changes." : message;
		error.setText(notice);
		apply.setEnabled(ledger != null);
	}

	void exported(String path)
	{
		JOptionPane.showMessageDialog(this, "Records saved to:\n" + path, "Export records",
			JOptionPane.PLAIN_MESSAGE);
	}

	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		return panel;
	}

	private static JPanel rateRow(JTextField field, boolean receivingModeB)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.add(field, BorderLayout.CENTER);
		row.add(new JLabel(receivingModeB ? "1 :" : ": 1"), receivingModeB ? BorderLayout.WEST : BorderLayout.EAST);
		return row;
	}

	private static JTextArea text()
	{
		JTextArea area = new JTextArea(0, 18)
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		area.setEditable(false);
		((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return area;
	}

	private static void setTextIfChanged(JTextComponent component, String value)
	{
		if (!value.equals(component.getText())) { component.setText(value); }
	}

	private static JTextArea note(String value)
	{
		JTextArea area = text();
		area.setText(value);
		area.setOpaque(true);
		area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		return area;
	}

	private static void addLine(JPanel panel, JComponent component)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		int maxHeight = component instanceof JScrollPane ? 100
			: component instanceof JTextField || component instanceof JComboBox || component instanceof JButton ? 30
			: component instanceof JLabel ? component.getPreferredSize().height : Integer.MAX_VALUE;
		component.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHeight));
		panel.add(component);
		panel.add(Box.createVerticalStrut(8));
	}

	private static void field(JPanel panel, String label, JTextField field)
	{
		JLabel title = new JLabel(label);
		title.setLabelFor(field);
		addLine(panel, title);
		addLine(panel, field);
		field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
	}

	static String gp(Number value) { return NumberFormat.getIntegerInstance(Locale.US).format(value); }
	static String date(long millis) { return DATE.format(Instant.ofEpochMilli(millis)); }
}
