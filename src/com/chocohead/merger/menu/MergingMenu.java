package com.chocohead.merger.menu;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.DoubleConsumer;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import matcher.gui.Gui;
import matcher.type.MatchType;

import com.chocohead.merger.MergeStep;

public class MergingMenu extends Menu {
	public static class MergeSettings {
		private final Set<MergeStep> steps = EnumSet.allOf(MergeStep.class);
		private final Gui gui;

		public MergeSettings(Gui gui) {
			this.gui = gui;
		}

		public boolean doStep(MergeStep step) {
			return steps.contains(step);
		}

		void setStep(MergeStep step, boolean use) {
			if (use) steps.add(step); else steps.remove(step);
		}

		public boolean hasAnySteps() {
			return !steps.isEmpty();
		}

		void run(DoubleConsumer progress) {
			assert hasAnySteps();
			int progressDivison = steps.size();

			for (MergeStep step : steps) {
				long previousUnmatched = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && !cls.hasMatch()).count();

				step.run(gui, value -> progress.accept(value / progressDivison));

				long unmatched = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && !cls.hasMatch()).count();
				System.out.println("Matched " + Math.abs(previousUnmatched - unmatched) + " classes (" + unmatched + " left unmatched, " + gui.getEnv().getClassesA().size() + " total)");
			}
		}

		void keepRunning(DoubleConsumer progress) {
			for (MergeStep step : MergeStep.values()) {
				long previousUnmatched = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && !cls.hasMatch()).count();

				step.run(gui, progress::accept);

				long unmatched = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && !cls.hasMatch()).count();
				System.out.println("Matched " + Math.abs(previousUnmatched - unmatched) + " classes (" + unmatched + " left unmatched, " + gui.getEnv().getClassesA().size() + " total)");
			}

			long matched = 0;
			do {
				long previousUnmatchedClasses = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && !cls.hasMatch()).count();
				long previousUnmatchedMethods = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && cls.hasMatch()).flatMap(cls -> Arrays.stream(cls.getMethods())).filter(method -> !method.hasMatch()).count();
				long previousUnmatchedFields = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && cls.hasMatch()).flatMap(cls -> Arrays.stream(cls.getFields())).filter(method -> !method.hasMatch()).count();

				MergeStep.UsageMatch.run(gui, progress::accept);

				long unmatchedClasses = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && !cls.hasMatch()).count();
				long unmatchedMethods = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && cls.hasMatch()).flatMap(cls -> Arrays.stream(cls.getMethods())).filter(method -> !method.hasMatch()).count();
				long unmatchedFields = gui.getEnv().getClassesA().stream().filter(cls -> cls.getUri() != null && cls.isNameObfuscated() && cls.hasMatch()).flatMap(cls -> Arrays.stream(cls.getFields())).filter(method -> !method.hasMatch()).count();

				matched = Math.abs(previousUnmatchedClasses - unmatchedClasses) + Math.abs(previousUnmatchedMethods - unmatchedMethods) + Math.abs(previousUnmatchedFields - unmatchedFields);
				System.out.println("Matched " + (unmatchedClasses - previousUnmatchedClasses) + " classes (" + unmatchedClasses + " left unmatched, " + gui.getEnv().getClassesA().size() + " total)");
			} while (matched > 0);
		}
	}

	public MergingMenu(Gui gui) {
		super("Merging");

		MergeSettings settings = new MergeSettings(gui);

		MenuItem item = new MenuItem("Run full process until complete");
		item.setOnAction(event -> {
			if (settings.hasAnySteps()) {
				gui.runProgressTask("Merging classes...",
						settings::keepRunning,
						() -> gui.onMatchChange(EnumSet.allOf(MatchType.class)),
						Throwable::printStackTrace);
			} else {
				Alert alert = new Alert(AlertType.INFORMATION);

				alert.setTitle("Merging classes...");
				alert.setHeaderText("No tasks selected");

				alert.showAndWait();
			}
		});
		getItems().add(item);
		item = new MenuItem("Run selected processes");
		item.setOnAction(event -> {
			if (settings.hasAnySteps()) {
				gui.runProgressTask("Merging classes...",
						settings::run,
						() -> gui.onMatchChange(EnumSet.allOf(MatchType.class)),
						Throwable::printStackTrace);
			} else {
				Alert alert = new Alert(AlertType.INFORMATION);

				alert.setTitle("Merging classes...");
				alert.setHeaderText("No tasks selected");

				alert.showAndWait();
			}
		});
		getItems().add(item);

		getItems().add(new SeparatorMenuItem());

		for (MergeStep step : MergeStep.values()) {
			CheckMenuItem checkItem = new CheckMenuItem(step.name);
			checkItem.setSelected(settings.doStep(step));
			checkItem.selectedProperty().addListener((observable, oldValue, newValue) -> {
				if (newValue != null) settings.setStep(step, newValue);
			});
			getItems().add(checkItem);
		}

		getItems().add(new SeparatorMenuItem());

		for (MergeStep step : MergeStep.values()) {
			item = new MenuItem(step.name);
			item.setOnAction(event -> gui.runProgressTask("Merging classes...",
					progress -> step.run(gui, progress),
					() -> gui.onMatchChange(EnumSet.allOf(MatchType.class)),
					Throwable::printStackTrace));
			getItems().add(item);
		}
	}
}