package edu.pbl.gitviz.ui;

import edu.pbl.gitviz.service.RepositorySession;
import edu.pbl.gitviz.service.git.CancellationToken;
import javafx.concurrent.Task;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
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
    private LoadAttempt inFlight;

    public MainWindow(Stage stage, AppContext context) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.context = Objects.requireNonNull(context, "context");
        this.welcomeView = new WelcomeView(this::openRepository);
        this.workspaceView = new WorkspaceView(context.timeDisplayProperty(), this::goHome, this::refresh);
        this.busyOverlay = createBusyOverlay();

        StackPane root = new StackPane(content, busyOverlay);
        // 读取期间窗口是模态的（§2.1）：鼠标点不到下面的界面，键盘也不行（包括 Tab 切到工具栏按钮后按回车）
        root.addEventFilter(KeyEvent.ANY, event -> {
            if (busyOverlay.isVisible()) {
                event.consume();
            }
        });
        busyOverlay.addEventFilter(MouseEvent.ANY, Event::consume);
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(Objects.requireNonNull(
                MainWindow.class.getResource("gitviz.css"), "gitviz.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::refresh);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN),
                workspaceView::showHistory);

        stage.setScene(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setOnHidden(event -> cancelLoading());
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
        cancelLoading();
        session = null;
        workspaceView.clear();
        showWelcome();
    }

    private void load(Path path, Consumer<RepositorySession> onLoaded) {
        if (inFlight != null) {
            return;
        }
        CancellationToken token = new CancellationToken();
        Task<RepositorySession> task = new Task<>() {
            @Override
            protected RepositorySession call() throws Exception {
                return context.repositoryLoader().load(path, token);
            }
        };
        task.setOnSucceeded(event -> {
            if (!isCurrent(task)) {
                return;
            }
            finishLoading();
            onLoaded.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            if (!isCurrent(task)) {
                return;
            }
            finishLoading();
            ErrorDialogs.showLoadFailure(stage, path, task.getException());
        });
        inFlight = new LoadAttempt(token, task);
        busyMessage.setText("正在读取提交历史…");
        busyOverlay.setVisible(true);
        context.runInBackground(task);
    }

    /**
     * 取消正在进行的读取：结束 git 子进程，并让它的结果不再影响界面。没有进行中的读取时什么也不做。
     * 回首页、关闭窗口时调用。
     */
    private void cancelLoading() {
        LoadAttempt attempt = inFlight;
        if (attempt == null) {
            return;
        }
        finishLoading();
        attempt.token.cancel();
        attempt.task.cancel();
    }

    /** 只有仍然是当前请求的结果才能更新界面；被取消的请求，结果直接丢弃。 */
    private boolean isCurrent(Task<?> task) {
        return inFlight != null && inFlight.task == task;
    }

    private void finishLoading() {
        inFlight = null;
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

    /** 一次正在进行的打开仓库请求。取消后，它的结果不再影响界面。 */
    private static final class LoadAttempt {

        private final CancellationToken token;
        private final Task<RepositorySession> task;

        LoadAttempt(CancellationToken token, Task<RepositorySession> task) {
            this.token = token;
            this.task = task;
        }
    }

    /** Windows 资源管理器"复制为路径"会带上双引号。 */
    private static String stripQuotes(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
