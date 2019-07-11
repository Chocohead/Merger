package com.chocohead.merger.pane;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Window;

import matcher.config.Config;
import matcher.gui.Gui;
import matcher.gui.Gui.SelectedFile;
import matcher.gui.GuiConstants;

public class ExportJarPane extends GridPane {
	public enum Type {
		Tiny("Tiny", "*.tiny"), CompressedTiny("Tiny (gzipped)", "*.tiny.gz"), TinyV2("Tiny v2", "*.tiny");

		public final String name, extension;

		private Type(String name, String extension) {
			this.name = name;
			this.extension = extension;
		}

		public ExtensionFilter toFilter() {
			return new ExtensionFilter(name, extension);
		}
	}
	public enum Side {
		A, B, BOTH;
	}

	private final Node okButton;
	private Path mergedJar, mappings;
	private String mappingType;
	private boolean serverFirst = true;
	private TextField excludedA, excludedB;
	private byte innerFixes;

	public ExportJarPane(Window window, Node okButton) {
		this.okButton = okButton;
		setHgap(GuiConstants.padding);
		setVgap(GuiConstants.padding);


		add(new Label("Export jar to:"), 0, 0);

		TextField jarExportLabel = new TextField();
		jarExportLabel.setPrefWidth(320);
		jarExportLabel.setEditable(false);
		jarExportLabel.setMouseTransparent(true);
		jarExportLabel.setFocusTraversable(false);
		add(jarExportLabel, 1, 0);

		Button jarExportButton = new Button("Select");
		jarExportButton.setOnAction(event -> {
			SelectedFile selection = Gui.requestFile("Saved merged jar", window, jarExtensionFilter(), false);

			if (selection != null) {
				jarExportLabel.setText(selection.path.toAbsolutePath().toString());
				mergedJar = selection.path;

				onConfigurationChange();
			}
		});
		add(jarExportButton, 2, 0);


		add(new Label("Export mappings to:"), 0, 1);

		TextField mappingExportLabel = new TextField();
		mappingExportLabel.setPrefWidth(320);
		mappingExportLabel.setEditable(false);
		mappingExportLabel.setMouseTransparent(true);
		mappingExportLabel.setFocusTraversable(false);
		add(mappingExportLabel, 1, 1);

		Button mappingExportButton = new Button("Select");
		mappingExportButton.setOnAction(event -> {
			SelectedFile selection = Gui.requestFile("Saved mappings file", window, mappingExtensionFilter(), false);

			if (selection != null) {
				mappingExportLabel.setText(selection.path.toAbsolutePath().toString());
				mappings = selection.path;
				mappingType = selection.filter.getDescription();

				onConfigurationChange();
			}
		});
		add(mappingExportButton, 2, 1);


		add(new Label("Jar merge order:"), 0, 2);

		GridPane grid = new GridPane();
		ToggleGroup mergeOrderGroup = new ToggleGroup();

		grid.add(new Label("A/B is "), 0, 0);

		ToggleButton serverClientToggle = new ToggleButton("Server/Client");
		serverClientToggle.setToggleGroup(mergeOrderGroup);
		serverClientToggle.setSelected(true);
		grid.add(serverClientToggle, 1, 0);

		ToggleButton clientServerToggle = new ToggleButton("Client/Server");
		clientServerToggle.setToggleGroup(mergeOrderGroup);
		grid.add(clientServerToggle, 2, 0);

		mergeOrderGroup.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
			serverFirst = newToggle == serverClientToggle;

			TextField temp = excludedA;
			excludedA = excludedB;
			excludedB = temp;
		});
		add(grid, 1, 2, 2, 1);


		Separator seperator = new Separator();
		seperator.setMinHeight(GuiConstants.padding * 3);
		add(seperator, 0, 3, 3, 1);


		add(new Label("A side deobf'd classes:"), 0, 4);

		excludedA = new TextField(Config.getProjectConfig().getNonObfuscatedClassPatternA());
		excludedA.setPrefWidth(320);
		excludedA.setTooltip(new Tooltip("Regex to filter classes which are fully deobfuscated already"));
		add(excludedA, 1, 4, 2, 1);


		add(new Label("B side deobf'd classes:"), 0, 5);

		excludedB = new TextField(Config.getProjectConfig().getNonObfuscatedClassPatternB());
		excludedB.setPrefWidth(320);
		excludedB.setTooltip(new Tooltip("Regex to filter classes which are fully deobfuscated already"));
		add(excludedB, 1, 5, 2, 1);


		add(new Label("Fix simple inner classes:"), 0, 6);

		HBox hBox = new HBox(GuiConstants.padding * 4);

		CheckBox checkA = new CheckBox("A (left)");
		hBox.getChildren().add(checkA);

		CheckBox checkB = new CheckBox("B (right)");
		hBox.getChildren().add(checkB);

		checkA.selectedProperty().addListener((observable, was, now) -> {
			if (now) {
				innerFixes |= 0x01;
			} else {
				innerFixes &= ~0x01;
			}
		});
		checkB.selectedProperty().addListener((observable, was, now) -> {
			if (now) {
				innerFixes |= 0x02;
			} else {
				innerFixes &= ~0x02;
			}
		});
		add(hBox, 1, 6, 2, 1);


		add(new Label("Fix all inner classes:"), 0, 7);

		hBox = new HBox(GuiConstants.padding * 4);

		CheckBox checkA2 = new CheckBox("A (left)");
		hBox.getChildren().add(checkA2);

		CheckBox checkB2 = new CheckBox("B (right)");
		hBox.getChildren().add(checkB2);

		checkA2.selectedProperty().addListener((observable, was, now) -> {
			if (now) {
				innerFixes |= 0x04;
				checkA.setSelected(true);
				checkA.setDisable(true);
			} else {
				innerFixes &= ~0x04;
				checkA.setDisable(false);
			}
		});
		checkB2.selectedProperty().addListener((observable, was, now) -> {
			if (now) {
				innerFixes |= 0x08;
				checkB.setSelected(true);
				checkB.setDisable(true);
			} else {
				innerFixes &= ~0x08;
				checkB.setDisable(false);
			}
		});
		add(hBox, 1, 7, 2, 1);

		onConfigurationChange();
	}

	static List<ExtensionFilter> jarExtensionFilter() {
		return Collections.singletonList(new ExtensionFilter("Java archive", "*.jar"));
	}

	private static List<ExtensionFilter> mappingExtensionFilter() {
		return Arrays.stream(Type.values()).map(Type::toFilter).collect(Collectors.toList());
	}

	void onConfigurationChange() {
		boolean valid = mergedJar != null && mappings != null && mappingType != null;
		okButton.setDisable(!valid);
	}

	public boolean isValid() {
		return !okButton.isDisabled();
	}

	public Path getMergeJar() {
		return mergedJar;
	}

	public Type getMappingsType() {
		for (Type type : Type.values()) {
			if (type.name.equals(mappingType)) {
				return type;
			}
		}

		assert mappingType != null; //Don't use this if the export is invalid
		throw new IllegalStateException("Unable to find mapping type: " + mappingType);
	}

	public Path getMappingsFile() {
		return mappings;
	}

	public boolean isServerA() {
		return serverFirst;
	}

	public boolean isClientA() {
		return !isServerA();
	}

	public String excludedA() {
		return excludedA.getText();
	}

	public String excludedB() {
		return excludedB.getText();
	}

	public boolean shouldFixAnyInners() {
		return innerFixes != 0;
	}

	public boolean shouldFixSimpleInners() {
		return (innerFixes & 0x3) != 0;
	}

	public boolean shouldFixAllInners() {
		return (innerFixes & 0xC) != 0;
	}

	public Side sidesToApply() {
		assert shouldFixAnyInners();
		return Side.values()[innerFixes - 1 & 0x3];
	}
}