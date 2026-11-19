package edu.pbl.gitviz.ui;

import edu.pbl.gitviz.service.RepositoryLoader;
import edu.pbl.gitviz.service.TimeDisplay;
import edu.pbl.gitviz.service.git.GitService;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 界面层用到的服务与共享状态，程序启动时创建一份。
 */
public final class AppContext {

    private final RepositoryLoader repositoryLoader;
    private final ObjectProperty<TimeDisplay> timeDisplay = new SimpleObjectProperty<>(TimeDisplay.defaults());
    private final ExecutorService background;

    private AppContext(RepositoryLoader repositoryLoader) {
        this.repositoryLoader = repositoryLoader;
        AtomicInteger counter = new AtomicInteger();
        this.background = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "gitviz-background-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public static AppContext createDefault() {
        return new AppContext(new RepositoryLoader(new GitService()));
    }

    public RepositoryLoader repositoryLoader() {
        return repositoryLoader;
    }

    /**
     * 当前的时间显示规则。所有显示时间的界面都监听它；设置页只需替换它的值。
     * 只在 JavaFX 应用线程读写。
     */
    public ObjectProperty<TimeDisplay> timeDisplayProperty() {
        return timeDisplay;
    }

    /** 在后台线程运行任务；Task 的回调会回到 JavaFX 应用线程。 */
    public void runInBackground(Task<?> task) {
        background.execute(task);
    }

    public void shutdown() {
        background.shutdownNow();
    }
}
