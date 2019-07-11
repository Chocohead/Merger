package com.chocohead.merger.pane;

import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

import matcher.gui.Gui;
import matcher.gui.Gui.SelectedFile;
import matcher.gui.GuiConstants;

import com.chocohead.merger.pane.ExportJarPane.Side;

public class ArgoPane extends GridPane {
	private final Node okButton;
	Path argoJar;
	Side applicationSide = Side.A;

	public ArgoPane(Window window, Node okButton) {
		this.okButton = okButton;
		setHgap(GuiConstants.padding);
		setVgap(GuiConstants.padding);


		add(new Label("Argo jar:"), 0, 1);

		TextField argoJarLabel = new TextField();
		argoJarLabel.setPrefWidth(320);
		argoJarLabel.setEditable(false);
		argoJarLabel.setMouseTransparent(true);
		argoJarLabel.setFocusTraversable(false);
		add(argoJarLabel, 1, 1);

		Button argoButton = new Button("Select");
		argoButton.setOnAction(event -> {
			SelectedFile selection = Gui.requestFile("Open Argo jar", window, ExportJarPane.jarExtensionFilter(), true);

			if (selection != null) {
				argoJarLabel.setText(selection.path.toAbsolutePath().toString());
				argoJar = selection.path;

				onConfigurationChange();
			}
		});
		add(argoButton, 2, 1);


		add(new Label("Apply Argo fix to:"), 0, 0);
		HBox hBox = new HBox(GuiConstants.padding * 4);
		ToggleGroup argoFixGroup = new ToggleGroup();

		RadioButton radioA = new RadioButton("A (left)");
		radioA.setToggleGroup(argoFixGroup);
		radioA.setSelected(true);
		hBox.getChildren().add(radioA);

		RadioButton radioB = new RadioButton("B (right)");
		radioB.setToggleGroup(argoFixGroup);
		hBox.getChildren().add(radioB);

		argoFixGroup.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
			if (newToggle == radioA) {
				applicationSide = Side.A;
			} else {
				assert newToggle == radioB;
				applicationSide = Side.B;
			}

			onConfigurationChange();
		});
		add(hBox, 1, 0, 2, 1);


		onConfigurationChange();
	}

	void onConfigurationChange() {
		boolean valid = argoJar != null;
		okButton.setDisable(!valid);
	}

	public boolean isValid() {
		return !okButton.isDisabled();
	}

	public Path getArgoJar() {
		return argoJar;
	}

	public Side getTargetSide() {
		return applicationSide;
	}
}