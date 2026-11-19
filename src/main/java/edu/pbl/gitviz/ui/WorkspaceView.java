package edu.pbl.gitviz.ui;

import edu.pbl.gitviz.model.HeadInfo;
import edu.pbl.gitviz.service.RepositorySession;
import edu.pbl.gitviz.service.TimeDisplay;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

/**
 * 工作区外壳（§2.1"工作区布局"）：顶部工具栏、左侧导航、中间内容区、底部状态栏。
 * 本 ticket 只有"提交历史"一个子页；其余子页和工具栏项由后续 ticket 加入。
 */
public final class WorkspaceView extends BorderPane {

    private static final String DETACHED_HEAD_HELP =
            "分离头指针：HEAD 没有指向任何分支，而是直接指向某个提交"
                    + "（例如检出了某个历史提交或标签）。\n"
                    + "在这种状态下做的新提交不属于任何分支，切换走之后可能很难再找到。";

    private final Label headLabel = new Label();
    private final Tooltip headTooltip = new Tooltip();
    private final Label commitCountLabel = new Label();
    private final Label shortcutsLabel = new Label();
    private final ToggleButton historyNav = new ToggleButton("提交历史");
    private final HistoryView historyView;

    public WorkspaceView(ObservableValue<TimeDisplay> timeDisplay, Runnable onHome, Runnable onRefresh) {
        getStyleClass().add("workspace-view");
        historyView = new HistoryView(timeDisplay);

        setTop(createToolbar(onHome, onRefresh));
        setLeft(createNavigation());
        setCenter(historyView);
        setBottom(createStatusBar());
        showHistory();
    }

    /** 显示一个新打开（或刷新后）的仓库。 */
    public void setSession(RepositorySession session) {
        HeadInfo head = session.head();
        if (head.detached()) {
            headLabel.setText("HEAD：分离头指针 · " + head.shortHash());
            headTooltip.setText(DETACHED_HEAD_HELP);
            headLabel.setTooltip(headTooltip);
        } else {
            headLabel.setText("HEAD：" + head.branchName() + " · " + head.shortHash());
            headLabel.setTooltip(null);
        }
        commitCountLabel.setText("共 " + session.commitCount() + " 条提交");
        historyView.setCommits(session.commits());
    }

    /** 离开工作区时释放对提交数据的引用。 */
    public void clear() {
        historyView.setCommits(List.of());
    }

    /** 切换到"提交历史"子页（Ctrl+1）。 */
    public void showHistory() {
        historyNav.setSelected(true);
        setCenter(historyView);
        shortcutsLabel.setText("F5 刷新 · Ctrl+1 提交历史");
    }

    private ToolBar createToolbar(Runnable onHome, Runnable onRefresh) {
        Button homeButton = new Button("⌂ 首页");
        homeButton.setTooltip(new Tooltip("回到欢迎页"));
        homeButton.setOnAction(event -> onHome.run());

        headLabel.getStyleClass().add("head-label");
        headTooltip.setShowDelay(Duration.millis(300));
        headTooltip.setWrapText(true);
        headTooltip.setMaxWidth(360);

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshButton = new Button("⟳ 刷新");
        refreshButton.setTooltip(new Tooltip("重新读取提交历史（F5）"));
        refreshButton.setOnAction(event -> onRefresh.run());

        ToolBar toolbar = new ToolBar(homeButton, headLabel, spacer, refreshButton);
        toolbar.getStyleClass().add("workspace-toolbar");
        return toolbar;
    }

    private VBox createNavigation() {
        ToggleGroup group = new ToggleGroup();
        historyNav.setToggleGroup(group);
        historyNav.setTooltip(new Tooltip("提交历史（Ctrl+1）"));
        historyNav.setMaxWidth(Double.MAX_VALUE);
        historyNav.setOnAction(event -> showHistory());
        // 导航项始终有一个处于选中状态：再次点击当前项不会取消选中
        group.selectedToggleProperty().addListener((observable, previous, current) -> {
            if (current == null && previous != null) {
                previous.setSelected(true);
            }
        });

        VBox navigation = new VBox(4, historyNav);
        navigation.getStyleClass().add("workspace-nav");
        return navigation;
    }

    private HBox createStatusBar() {
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(12, commitCountLabel, spacer, shortcutsLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");
        return statusBar;
    }
}
