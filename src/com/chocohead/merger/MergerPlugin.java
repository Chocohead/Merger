package com.chocohead.merger;

import matcher.Plugin;
import matcher.gui.Gui;

import com.chocohead.merger.menu.MergedJaringMenu;
import com.chocohead.merger.menu.MergingMenu;

public class MergerPlugin implements Plugin {


	@Override
	public String getName() {
		return "Merger";
	}

	@Override
	public String getVersion() {
		return "1.0";
	}

	@Override
	public void init(int pluginApiVersion) {
		System.out.println("We've run \\o/");

		Gui.loadListeners.add(gui -> {
			gui.getMenu().getMenus().add(2, new MergingMenu(gui));
			gui.getMenu().getMenus().add(3, new MergedJaringMenu(gui));
		});
	}
}