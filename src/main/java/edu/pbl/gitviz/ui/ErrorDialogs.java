package edu.pbl.gitviz.ui;

import edu.pbl.gitviz.service.git.GitException;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

/**
 * 简单的错误提示：用中文说明原因，git 原始输出放进默认折叠的"技术详情"。
 * 完整的错误分类和 P9 消息提示对话框由后续 ticket 实现。
 */
final class ErrorDialogs {

    private ErrorDialogs() {
    }

    static void showLoadFailure(Window owner, Path path, Throwable error) {
        String message;
        String details;
        if (error instanceof GitException gitError) {
            message = gitError.userMessage();
            details = gitError.technicalDetails();
        } else {
            message = "读取仓库时发生了意外错误。";
            details = stackTrace(error);
        }
        show(owner, "无法打开仓库", message + "\n\n路径：" + path, details);
    }

    static void showInvalidPath(Window owner, String text) {
        show(owner, "路径格式不正确", "无法识别这个路径，请检查是否有多余的字符：\n" + text, null);
    }

    private static void show(Window owner, String header, String content, String details) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle(header);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.getDialogPane().setPrefWidth(560);
        if (details != null) {
            TextArea area = new TextArea(details);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(10);
            area.getStyleClass().add("technical-details");
            alert.getDialogPane().setExpandableContent(new VBox(6, new Label("技术详情（可复制）"), area));
        }
        alert.showAndWait();
    }

    private static String stackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
