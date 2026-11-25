package edu.pbl.gitviz;

import edu.pbl.gitviz.ui.AppContext;
import edu.pbl.gitviz.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * JavaFX 应用。{@code mvnw javafx:run} 直接启动它。
 */
public final class GitVizApp extends Application {

    private AppContext context;

    @Override
    public void start(Stage stage) {
        context = AppContext.createDefault();
        new MainWindow(stage, context).show();
    }

    @Override
    public void stop() {
        if (context != null) {
            context.shutdown();
        }
    }
}
