package com.swaputil;

import java.awt.Component;
import java.awt.Container;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import org.junit.Test;
import static org.junit.Assert.*;

public class SidebarRefreshTest
{
	@Test
	public void twoClientsSaveDifferentRatesWithoutOverwritingEachOther() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			Ledger shared = LedgerTest.configured();
			Gson gson = new Gson();
			SwapUtilPanel first = new SwapUtilPanel(action -> action.accept(shared), () -> {});
			SwapUtilPanel second = new SwapUtilPanel(action -> action.accept(shared), () -> {});
			first.render(gson.fromJson(gson.toJson(shared), Ledger.class), "My main", "");
			second.render(gson.fromJson(gson.toJson(shared), Ledger.class), "My alt", "");
			JComboBox<?> firstDirection = direction(first);
			JComboBox<?> secondDirection = direction(second);
			firstDirection.setSelectedIndex(0);
			secondDirection.setSelectedIndex(1);
			fieldWithValue(first, "900").setText("1000");
			fieldWithValue(second, "1500").setText("1100");
			saveButton(first).doClick();
			assertEquals("1000", shared.rates().buy);
			assertEquals("1500", shared.rates().sell);
			second.render(gson.fromJson(gson.toJson(shared), Ledger.class), "My alt", "");
			assertEquals(1, secondDirection.getSelectedIndex());
			assertNotNull(fieldWithValue(second, "1100"));
			saveButton(second).doClick();
			assertEquals("1000", shared.rates().buy);
			assertEquals("1100", shared.rates().sell);
			assertEquals(LedgerTest.configured().group, shared.group);
			assertEquals(Integer.valueOf(0), shared.accountModes.get("my main"));
			assertEquals(Integer.valueOf(1), shared.accountModes.get("my alt"));
			first.saved();
			first.render(gson.fromJson(gson.toJson(shared), Ledger.class), "My main", "");
			assertEquals(0, firstDirection.getSelectedIndex());
		});
	}

	@Test
	public void rateSavePreservesAnUnseenPartnerUpdate() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			Ledger shared = LedgerTest.configured();
			SwapUtilPanel panel = new SwapUtilPanel(action -> action.accept(shared), () -> {});
			panel.render(new Gson().fromJson(new Gson().toJson(shared), Ledger.class), "My main", "");
			fieldWithValue(panel, "900").setText("950");
			String newGroup = shared.group + "\nNew partner = New alt";
			shared.configure(newGroup, null, null, null, "1600");
			saveButton(panel).doClick();
			assertEquals(newGroup, shared.group);
			assertEquals("950", shared.rates().buy);
			assertEquals("1600", shared.rates().sell);
		});
	}

	private static JTextComponent fieldWithValue(Container parent, String value)
	{
		return fields(parent).stream().filter(field -> field.isEditable() && value.equals(field.getText())).findFirst().orElseThrow();
	}

	private static JComboBox<?> direction(Container parent)
	{
		return (JComboBox<?>) components(parent).stream().filter(component -> component instanceof JComboBox
			&& ((JComboBox<?>) component).getItemCount() == 2
			&& "Hardcore \u2192 OSRS".equals(((JComboBox<?>) component).getItemAt(0))).findFirst().orElseThrow();
	}

	private static JButton saveButton(Container parent)
	{
		return (JButton) components(parent).stream().filter(component -> component instanceof JButton
			&& "Save settings".equals(((JButton) component).getText())).findFirst().orElseThrow();
	}

	private static List<Component> components(Container parent)
	{
		List<Component> result = new ArrayList<>();
		for (Component child : parent.getComponents())
		{
			result.add(child);
			if (child instanceof Container) { result.addAll(components((Container) child)); }
		}
		return result;
	}

	@Test
	public void unchangedRefreshPreservesDocumentsAndTextSelection() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			Ledger ledger = LedgerTest.configured();
			SwapUtilPanel panel = new SwapUtilPanel(action -> {}, () -> {});
			panel.render(ledger, "My main", "");
			AtomicInteger edits = new AtomicInteger();
			List<JTextComponent> fields = fields(panel);
			for (JTextComponent field : fields)
			{
				field.select(0, field.getDocument().getLength());
				field.getDocument().addDocumentListener(new DocumentListener()
				{
					public void insertUpdate(DocumentEvent event) { edits.incrementAndGet(); }
					public void removeUpdate(DocumentEvent event) { edits.incrementAndGet(); }
					public void changedUpdate(DocumentEvent event) { edits.incrementAndGet(); }
				});
			}
			for (int i = 0; i < 5; i++) { panel.render(ledger, "My main", ""); }
			assertEquals(0, edits.get());
			for (JTextComponent field : fields)
			{
				assertEquals(field.getDocument().getLength(), field.getSelectionEnd());
			}
		});
	}

	@Test
	public void displayingAnErrorDoesNotAskTheViewportToFollowItsCaret() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			Ledger ledger = LedgerTest.configured();
			SwapUtilPanel panel = new SwapUtilPanel(action -> {}, () -> {});
			panel.render(ledger, "My main", "");
			panel.render(ledger, "My main", "Could not save records.");
			for (JTextComponent field : fields(panel))
			{
				if (!field.isEditable())
				{
					assertEquals(DefaultCaret.NEVER_UPDATE, ((DefaultCaret) field.getCaret()).getUpdatePolicy());
				}
			}
			assertTrue(fields(panel).stream().anyMatch(field -> field.getText().equals("Could not save records.")));
		});
	}

	private static List<JTextComponent> fields(Container parent)
	{
		List<JTextComponent> result = new ArrayList<>();
		for (Component child : parent.getComponents())
		{
			if (child instanceof JTextComponent) { result.add((JTextComponent) child); }
			else if (child instanceof Container) { result.addAll(fields((Container) child)); }
		}
		return result;
	}
}
