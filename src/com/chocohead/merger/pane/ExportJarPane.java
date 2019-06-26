package com.chocohead.merger.pane;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Window;

import matcher.gui.Gui;
import matcher.gui.Gui.SelectedFile;
import matcher.gui.GuiConstants;

public class ExportJarPane extends GridPane {
	private final Node okButton;
	private Path mergedJar, mappings, argoJar;
	private String mappingType;
	private boolean serverFirst = true, applyArgo;
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


		Separator seperator = new Separator();
		seperator.setMinHeight(GuiConstants.padding * 3);
		add(seperator, 0, 3, 3, 1);


		add(new Label("Argo jar:"), 0, 5);

		TextField argoJarLabel = new TextField();
		argoJarLabel.setPrefWidth(320);
		argoJarLabel.setEditable(false);
		argoJarLabel.setMouseTransparent(true);
		argoJarLabel.setFocusTraversable(false);
		argoJarLabel.setDisable(true);
		add(argoJarLabel, 1, 5);

		Button argoButton = new Button("Select");
		argoButton.setOnAction(event -> {
			SelectedFile selection = Gui.requestFile("Open Argo jar", window, jarExtensionFilter(), true);

			if (selection != null) {
				argoJarLabel.setText(selection.path.toAbsolutePath().toString());
				argoJar = selection.path;

				onConfigurationChange();
			}
		});
		argoButton.setDisable(true);
		add(argoButton, 2, 5);


		add(new Label("Apply Argo fix to:"), 0, 4);

		HBox hBox = new HBox(GuiConstants.padding * 4);
		ToggleGroup argoFixGroup = new ToggleGroup();

		RadioButton radioN = new RadioButton("Neither");
		radioN.setToggleGroup(argoFixGroup);
		radioN.setSelected(true);
		hBox.getChildren().add(radioN);

		RadioButton radioA = new RadioButton("A (left)");
		radioA.setToggleGroup(argoFixGroup);
		radioA.setDisable(true);
		hBox.getChildren().add(radioA);

		RadioButton radioB = new RadioButton("B (right)");
		radioB.setToggleGroup(argoFixGroup);
		hBox.getChildren().add(radioB);

		argoFixGroup.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
			boolean disable = newToggle == radioN;
			argoJarLabel.setDisable(disable);
			argoButton.setDisable(disable);

			applyArgo = !disable;
			onConfigurationChange();
		});
		add(hBox, 1, 4, 2, 1);


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
			if (serverFirst = newToggle == serverClientToggle) {
				if (radioA.isSelected()) {
					radioB.setSelected(true);
				}
				radioA.setDisable(true);
				radioB.setDisable(false);
			} else {
				if (radioB.isSelected()) {
					radioA.setSelected(true);
				}
				radioA.setDisable(false);
				radioB.setDisable(true);
			}
		});
		add(grid, 1, 2, 2, 1);


		add(new Label("Fix inner classes:"), 0, 6);

		hBox = new HBox(GuiConstants.padding * 4);

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

		onConfigurationChange();
	}

	private static List<ExtensionFilter> jarExtensionFilter() {
		return Collections.singletonList(new ExtensionFilter("Java archive", "*.jar"));
	}

	private static List<ExtensionFilter> mappingExtensionFilter() {
		return Arrays.asList(new ExtensionFilter("Tiny", "*.tiny"), new ExtensionFilter("Tiny v2", "*.tiny"));
	}

	void onConfigurationChange() {
		boolean valid = mergedJar != null && mappings != null && mappingType != null && (!applyArgo || argoJar != null);
		okButton.setDisable(!valid);
	}

	public boolean isValid() {
		return !okButton.isDisabled();
	}

	public Path getMergeJar() {
		return mergedJar;
	}

	public String getMappingsType() {
		return mappingType;
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

	public boolean shouldFixArgo() {
		return applyArgo;
	}

	public Path getArgoJar() {
		assert shouldFixArgo();
		return argoJar;
	}

	public boolean shouldFixInners() {
		return innerFixes != 0;
	}

	public byte sidesToApply() {
		assert shouldFixInners();
		return innerFixes;
	}
}