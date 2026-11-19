package edu.pbl.gitviz.ui;

import edu.pbl.gitviz.service.RepositorySession;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 主窗口（§2.1）：单窗口应用，根视图在欢迎页（P1）和工作区之间切换。
 */
public final class MainWindow {

    static final double DEFAULT_WIDTH = 1280;
    static final double DEFAULT_HEIGHT = 800;
    static final double MIN_WIDTH = 960;
    static final double MIN_HEIGHT = 640;

    private static final String BASE_TITLE = "Git 提交历史可视化与仓库分析工具";

    private final Stage stage;
    private final AppContext context;
    private final StackPane content = new StackPane();
    private final StackPane busyOverlay;
    private final Label busyMessage = new Label();
    private final WelcomeView welcomeView;
    private final WorkspaceView workspaceView;

    private RepositorySession session;
    private Task<RepositorySession> loading;

    public MainWindow(Stage stage, AppContext context) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.context = Objects.requireNonNull(context, "context");
        this.welcomeView = new WelcomeView(this::openRepository);
        this.workspaceView = new WorkspaceView(context.timeDisplayProperty(), this::goHome, this::refresh);
        this.busyOverlay = createBusyOverlay();

        StackPane root = new StackPane(content, busyOverlay);
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(Objects.requireNonNull(
                MainWindow.class.getResource("gitviz.css"), "gitviz.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::refresh);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN),
                workspaceView::showHistory);

        stage.setScene(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        showWelcome();
    }

    public void show() {
        stage.show();
        welcomeView.focusPathField();
    }

    /** P1 点"打开"：在后台读取仓库，成功后进入工作区。 */
    void openRepository(String pathText) {
        Path path;
        try {
            path = Path.of(stripQuotes(pathText.strip()));
        } catch (InvalidPathException e) {
            ErrorDialogs.showInvalidPath(stage, pathText);
            return;
        }
        load(path, this::showWorkspace);
    }

    /** 工具栏"刷新"或 F5：重新读取当前仓库并替换会话；失败时保留原会话。 */
    void refresh() {
        if (session != null) {
            load(session.repoRoot(), this::showWorkspace);
        }
    }

    /** 工具栏"首页"：回到 P1 并释放会话。 */
    void goHome() {
        session = null;
        workspaceView.clear();
        showWelcome();
    }

    private void load(Path path, Consumer<RepositorySession> onLoaded) {
        if (loading != null) {
            return;
        }
        Task<RepositorySession> task = new Task<>() {
            @Override
            protected RepositorySession call() throws Exception {
                return context.repositoryLoader().load(path);
            }
        };
        task.setOnSucceeded(event -> {
            finishLoading();
            onLoaded.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            finishLoading();
            ErrorDialogs.showLoadFailure(stage, path, task.getException());
        });
        loading = task;
        busyMessage.setText("正在读取提交历史…");
        busyOverlay.setVisible(true);
        context.runInBackground(task);
    }

    private void finishLoading() {
        loading = null;
        busyOverlay.setVisible(false);
    }

    private void showWelcome() {
        content.getChildren().setAll(welcomeView);
        stage.setTitle(BASE_TITLE);
        welcomeView.focusPathField();
    }

    private void showWorkspace(RepositorySession newSession) {
        session = newSession;
        workspaceView.setSession(newSession);
        content.getChildren().setAll(workspaceView);
        stage.setTitle(BASE_TITLE + " — " + newSession.displayName());
    }

    /** 读取期间盖在界面上，阻止重复操作。进度对话框（P2）由后续 ticket 实现。 */
    private StackPane createBusyOverlay() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMaxSize(48, 48);
        VBox box = new VBox(12, indicator, busyMessage);
        box.setAlignment(Pos.CENTER);
        StackPane overlay = new StackPane(box);
        overlay.getStyleClass().add("busy-overlay");
        overlay.setVisible(false);
        return overlay;
    }

    /** Windows 资源管理器"复制为路径"会带上双引号。 */
    private static String stripQuotes(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
