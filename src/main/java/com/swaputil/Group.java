package com.swaputil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class Group
{
	private final Map<String, String> accounts = new LinkedHashMap<>();

	static String normalize(String name)
	{
		return name == null ? "" : name.replace('\u00a0', ' ').replace('_', ' ')
			.trim().replaceAll(" +", " ").toLowerCase(Locale.ROOT);
	}

	static Group parse(String text)
	{
		Group group = new Group();
		Set<String> people = new HashSet<>();
		for (String line : text.split("\\R"))
		{
			if (line.trim().isEmpty()) { continue; }
			String[] parts = line.split("=", -1);
			if (parts.length == 1) { parts = new String[]{line, line}; }
			if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty())
			{
				throw new IllegalArgumentException("Enter one RSN per line.");
			}
			String person = parts[0].trim();
			if (!people.add(normalize(person)))
			{
				throw new IllegalArgumentException("Each group member should appear only once.");
			}
			for (String account : parts[1].split(",", -1))
			{
				String key = normalize(account);
				if (key.isEmpty() || key.length() > 12 || !key.matches("[a-z0-9 -]+"))
				{
					throw new IllegalArgumentException("Enter a valid RSN on each line.");
				}
				if (group.accounts.putIfAbsent(key, person) != null)
				{
					throw new IllegalArgumentException("Each character can belong to only one person.");
				}
			}
		}
		return group;
	}

	String person(String account) { return accounts.get(normalize(account)); }

	List<String> participants(String account, Collection<String> online)
	{
		String owner = person(account);
		if (owner == null) { throw new IllegalArgumentException("Add this account to your group first."); }
		TreeSet<String> present = new TreeSet<>();
		present.add(owner);
		for (String name : online)
		{
			String partner = person(name);
			if (partner != null) { present.add(partner); }
		}
		return new ArrayList<>(present);
	}
}
