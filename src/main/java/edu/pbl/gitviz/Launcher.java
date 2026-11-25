package edu.pbl.gitviz;

import javafx.application.Application;

/**
 * 普通 main 入口。直接从 IDE 运行或打包成 jar 时用它启动：
 * 入口类如果直接继承 {@link Application}，在类路径上启动会报"缺少 JavaFX 运行时组件"。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(GitVizApp.class, args);
    }
}
