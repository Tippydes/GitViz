package edu.pbl.gitviz.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * P1 欢迎页（§2.2.1）。本 ticket 只有路径输入框和"打开"按钮；
 * 拖放、目录选择器、Git 环境状态、最近仓库列表由后续 ticket 加入。
 */
public final class WelcomeView extends BorderPane {

    private final TextField pathField = new TextField();

    public WelcomeView(Consumer<String> onOpen) {
        Objects.requireNonNull(onOpen, "onOpen");
        getStyleClass().add("welcome-view");

        Label title = new Label("Git 提交历史可视化与仓库分析工具");
        title.getStyleClass().add("welcome-title");
        Label subtitle = new Label("选择一个本地 Git 仓库，查看它的提交历史");
        subtitle.getStyleClass().add("welcome-subtitle");

        Label pathLabel = new Label("仓库路径");
        pathLabel.getStyleClass().add("field-label");
        pathField.setPromptText("输入或粘贴仓库根目录的路径，例如 D:\\pbl\\git-visualizer");
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button openButton = new Button("打开");
        openButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> pathField.getText() == null || pathField.getText().isBlank(), pathField.textProperty()));
        // 回车（TextField 的 onAction）和"打开"按钮走同一个入口；不用 defaultButton，避免同一次回车触发两次
        Runnable submit = () -> {
            if (!pathField.getText().isBlank()) {
                onOpen.accept(pathField.getText());
            }
        };
        pathField.setOnAction(event -> submit.run());
        openButton.setOnAction(event -> submit.run());

        HBox inputRow = new HBox(8, pathField, openButton);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, pathLabel, inputRow);
        card.getStyleClass().add("welcome-card");
        card.setMaxWidth(720);

        VBox column = new VBox(24, new VBox(6, title, subtitle), card);
        column.setAlignment(Pos.CENTER_LEFT);
        column.setMaxWidth(720);
        setCenter(column);
    }

    public void focusPathField() {
        Platform.runLater(pathField::requestFocus);
    }
}
