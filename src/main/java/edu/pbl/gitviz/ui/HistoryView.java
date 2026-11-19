package edu.pbl.gitviz.ui;

import edu.pbl.gitviz.model.CommitRecord;
import edu.pbl.gitviz.service.TimeDisplay;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * P3 提交历史页（§2.2.2）。本 ticket 只有提交列表的基础列：
 * 短哈希、提交说明、作者、提交时间、增删行，按作者时间倒序。
 *
 * <p>TableView 只渲染可见的行，上万条提交也能流畅滚动。
 */
public final class HistoryView extends VBox {

    private static final double ROW_HEIGHT = 30;

    private final TableView<CommitRecord> table = new TableView<>();
    private final Label summaryLabel = new Label();
    private final ObservableValue<TimeDisplay> timeDisplay;

    public HistoryView(ObservableValue<TimeDisplay> timeDisplay) {
        this.timeDisplay = timeDisplay;
        getStyleClass().add("history-view");

        Label title = new Label("提交列表");
        title.getStyleClass().add("section-title");
        summaryLabel.getStyleClass().add("muted");
        HBox header = new HBox(12, title, summaryLabel);
        header.setAlignment(Pos.BASELINE_LEFT);

        configureTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(header, table);

        // 时间显示规则变化（设置页切换时区或格式）时重绘
        timeDisplay.addListener((observable, previous, current) -> table.refresh());
    }

    public void setCommits(List<CommitRecord> commits) {
        table.setItems(FXCollections.observableArrayList(commits));
        summaryLabel.setText(commits.isEmpty() ? "" : "共 " + commits.size() + " 条，按提交时间倒序");
        if (!commits.isEmpty()) {
            table.scrollTo(0);
        }
    }

    private void configureTable() {
        TableColumn<CommitRecord, String> hashColumn = new TableColumn<>("哈希");
        hashColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().shortHash()));
        hashColumn.getStyleClass().add("hash-column");
        sized(hashColumn, 80, 90, 110);

        TableColumn<CommitRecord, String> subjectColumn = new TableColumn<>("提交说明");
        subjectColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().subject()));
        subjectColumn.setCellFactory(column -> new SubjectCell());
        sized(subjectColumn, 200, 520, Double.MAX_VALUE);

        TableColumn<CommitRecord, String> authorColumn = new TableColumn<>("作者");
        authorColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().authorName()));
        sized(authorColumn, 90, 140, 220);

        TableColumn<CommitRecord, CommitRecord> timeColumn = new TableColumn<>("提交时间");
        timeColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        timeColumn.setCellFactory(column -> new TimeCell());
        sized(timeColumn, 130, 150, 200);

        TableColumn<CommitRecord, CommitRecord> changesColumn = new TableColumn<>("变更");
        changesColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        changesColumn.setCellFactory(column -> new ChangesCell());
        sized(changesColumn, 100, 130, 170);

        List<TableColumn<CommitRecord, ?>> columns =
                List.of(hashColumn, subjectColumn, authorColumn, timeColumn, changesColumn);
        for (TableColumn<CommitRecord, ?> column : columns) {
            // 顺序固定为作者时间倒序，不允许点表头重排
            column.setSortable(false);
            column.setReorderable(false);
        }
        table.getColumns().setAll(columns);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(ROW_HEIGHT);
        table.setPlaceholder(new Label("没有可显示的提交"));
        table.getStyleClass().add("commit-table");
    }

    private static void sized(TableColumn<?, ?> column, double min, double pref, double max) {
        column.setMinWidth(min);
        column.setPrefWidth(pref);
        column.setMaxWidth(max);
    }

    /** 提交说明可能为空串，显示为灰色的占位文字。 */
    private static final class SubjectCell extends TableCell<CommitRecord, String> {
        @Override
        protected void updateItem(String subject, boolean empty) {
            super.updateItem(subject, empty);
            getStyleClass().remove("muted");
            if (empty || subject == null) {
                setText(null);
            } else if (subject.isEmpty()) {
                setText("（无提交说明）");
                getStyleClass().add("muted");
            } else {
                setText(subject);
            }
        }
    }

    private final class TimeCell extends TableCell<CommitRecord, CommitRecord> {
        @Override
        protected void updateItem(CommitRecord commit, boolean empty) {
            super.updateItem(commit, empty);
            setText(empty || commit == null ? null : timeDisplay.getValue().format(commit));
        }
    }

    /** "+128 −36"：新增绿色、删除红色；二进制文件不计入。 */
    private static final class ChangesCell extends TableCell<CommitRecord, CommitRecord> {

        private final Label additions = new Label();
        private final Label deletions = new Label();
        private final HBox box = new HBox(6, additions, deletions);

        ChangesCell() {
            additions.getStyleClass().add("additions");
            deletions.getStyleClass().add("deletions");
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(CommitRecord commit, boolean empty) {
            super.updateItem(commit, empty);
            if (empty || commit == null) {
                setGraphic(null);
            } else {
                additions.setText("+" + commit.additions());
                deletions.setText("−" + commit.deletions());
                setGraphic(box);
            }
        }
    }
}
