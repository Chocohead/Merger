package com.chocohead.merger.pane;

import java.util.Comparator;
import java.util.List;

import javafx.collections.FXCollections;
import javafx.scene.control.Alert;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;

import matcher.NameType;
import matcher.Util;
import matcher.type.ClassEnvironment;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.Matchable;
import matcher.type.MemberInstance;
import matcher.type.MethodInstance;

public class ArgoConfirmPane extends Alert {
	final ListView<MemberInstance<?>> matchList;

	public ArgoConfirmPane(ClassEnvironment env) {
		super(AlertType.CONFIRMATION);

		setResizable(true);
		setTitle("Library matching confirmation");


		matchList = new ListView<>();
		matchList.setCellFactory(ignore -> new ListCell<MemberInstance<?>>() {
			private String getText(MemberInstance<?> member) {
				String name = member.getDisplayName(NameType.PLAIN, false);

				if (!member.hasMatch()) {
					return name;
				} else {
					return name + " => " + member.getMatch().getDisplayName(NameType.PLAIN, false);
				}
			}

			private String getStyle(Matchable<?> member) {
				if (!member.hasMatch()) {
					return "-fx-text-fill: darkred;";
				} else {
					return "-fx-text-fill: darkgreen;";
				}
			}

			@Override
			protected void updateItem(MemberInstance<?> item, boolean empty) {
				super.updateItem(item, empty);

				if (empty || item == null) {
					setText(null);
					setStyle("");
				} else {
					setText(getText(item));
					setStyle(getStyle(item));
				}
			}
		});
		matchList.setItems(FXCollections.observableArrayList());
		matchList.setMouseTransparent(true);
		matchList.setFocusTraversable(false);

		ListView<ClassInstance> classList = new ListView<>();
		classList.setCellFactory(ignore -> new ListCell<ClassInstance>() {
			private String getText(ClassInstance cls) {
				String name = cls.getDisplayName(NameType.PLAIN, true);
				String mappedName = cls.getDisplayName(NameType.MAPPED_PLAIN, true);

				if (name.equals(mappedName)) {
					return name;
				} else {
					return name + " - " + mappedName;
				}
			}

			private String getStyle(Matchable<?> cls) {
				if (!cls.hasMatch()) {
					return "-fx-text-fill: darkred;";
				} else if (!cls.isFullyMatched(false)) {
					return "-fx-text-fill: chocolate;";
				} else {
					return "-fx-text-fill: darkgreen;";
				}
			}

			@Override
			protected void updateItem(ClassInstance item, boolean empty) {
				super.updateItem(item, empty);

				if (empty || item == null) {
					setText(null);
					setStyle("");
				} else {
					setText(getText(item));
					setStyle(getStyle(item));
				}
			}
		});
		classList.setItems(FXCollections.observableList(env.getDisplayClassesA(true)));
		classList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
			if (oldValue == newValue) return;

			List<MemberInstance<?>> items = matchList.getItems();
			items.clear();

			if (newValue != null) {
				for (MethodInstance m : newValue.getMethods()) {
					if (m.isReal()) items.add(m);
				}

				for (FieldInstance m : newValue.getFields()) {
					if (m.isReal()) items.add(m);
				}

				items.sort(((Comparator<MemberInstance<?>>) (a, b) -> {
					boolean aIsMethod = a instanceof MethodInstance;
					boolean bIsMethod = b instanceof MethodInstance;

					if (aIsMethod == bIsMethod) {
						return 0;
					} else {
						return aIsMethod ? -1 : 1;
					}
				}).thenComparing(m -> m.getDisplayName(NameType.PLAIN, false), Util::compareNatural));
			}
		});

		SplitPane pane = new SplitPane();
		pane.getItems().add(classList);
		pane.getItems().add(matchList);

		pane.setDividerPosition(0, 0.4);
		getDialogPane().setContent(pane);
	}
}