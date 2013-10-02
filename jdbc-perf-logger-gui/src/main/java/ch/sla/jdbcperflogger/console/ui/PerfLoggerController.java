package ch.sla.jdbcperflogger.console.ui;

import java.io.CharArrayWriter;
import java.io.File;
import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import ch.sla.jdbcperflogger.console.db.LogRepository;
import ch.sla.jdbcperflogger.console.db.ResultSetAnalyzer;
import ch.sla.jdbcperflogger.console.net.AbstractLogReceiver;
import ch.sla.jdbcperflogger.model.StatementLog;

public class PerfLoggerController {
    private final AbstractLogReceiver logReceiver;
    private final LogRepository logRepository;
    private final IClientConnectionDelegate clientConnectionDelegate;
    private final LogExporter logExporter;
    private final PerfLoggerPanel perfLoggerPanel;

    private interface SelectLogRunner {
        void doSelect(ResultSetAnalyzer resultSetAnalyzer);
    }

    private final SelectLogRunner selectAllLogStatements = new SelectLogRunner() {
        @Override
        public void doSelect(ResultSetAnalyzer resultSetAnalyzer) {
            logRepository.getStatements(txtFilter, minDurationNanos, resultSetAnalyzer);
        }
    };
    private final SelectLogRunner selectLogStatementsGroupByRawSql = new SelectLogRunner() {
        @Override
        public void doSelect(ResultSetAnalyzer resultSetAnalyzer) {
            logRepository.getStatementsGroupByRawSQL(txtFilter, minDurationNanos, resultSetAnalyzer);
        }
    };
    private final SelectLogRunner selectLogStatementsGroupByFilledSql = new SelectLogRunner() {
        @Override
        public void doSelect(ResultSetAnalyzer resultSetAnalyzer) {
            logRepository.getStatementsGroupByFilledSQL(txtFilter, minDurationNanos, resultSetAnalyzer);
        }
    };
    private volatile String txtFilter;
    private volatile Long minDurationNanos;
    private SelectLogRunner currentSelectLogRunner = selectAllLogStatements;
    private RefreshDataTask refreshDataTask;
    private boolean tableStructureChanged = true;
    private GroupBy groupBy = GroupBy.NONE;

    PerfLoggerController(IClientConnectionDelegate clientConnectionDelegate, AbstractLogReceiver logReceiver,
            LogRepository logRepository) {
        this.clientConnectionDelegate = clientConnectionDelegate;
        this.logReceiver = logReceiver;
        this.logRepository = logRepository;

        logExporter = new LogExporter(logRepository);

        perfLoggerPanel = new PerfLoggerPanel(this);
        initGUI();
    }

    private void initGUI() {
        perfLoggerPanel.setCloseEnable(!logReceiver.isServerMode());
        final Timer timer = new Timer(true);
        refreshDataTask = new RefreshDataTask();
        timer.schedule(refreshDataTask, 1000, 1000);

    }

    JPanel getPanel() {
        return perfLoggerPanel;
    }

    void setTextFilter(String filter) {
        System.out.println(filter);
        if (filter == null || filter.isEmpty()) {
            txtFilter = null;
        } else {
            txtFilter = filter;
        }
        refresh();
    }

    void setMinDurationFilter(Long durationMs) {
        if (durationMs == null) {
            minDurationNanos = null;
        } else {
            minDurationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
        }
        refresh();
    }

    void setGroupBy(GroupBy groupBy) {
        this.groupBy = groupBy;
        switch (groupBy) {
        case NONE:
            currentSelectLogRunner = selectAllLogStatements;
            break;
        case RAW_SQL:
            currentSelectLogRunner = selectLogStatementsGroupByRawSql;
            break;
        case FILLED_SQL:
            currentSelectLogRunner = selectLogStatementsGroupByFilledSql;
            break;
        }
        tableStructureChanged = true;
        refresh();
    }

    void onSelectStatement(Long logId) {
        statementSelected(logId);
    }

    void onClear() {
        logRepository.clear();
        refresh();

    }

    void onPause() {
        if (logReceiver.isPaused()) {
            logReceiver.resumeReceivingLogs();
            perfLoggerPanel.btnPause.setText("Pause");
        } else {
            logReceiver.pauseReceivingLogs();
            perfLoggerPanel.btnPause.setText("Resume");
        }

    }

    void onClose() {
        refreshDataTask.cancel();
        logReceiver.dispose();
        logRepository.dispose();
        clientConnectionDelegate.close(perfLoggerPanel);
    }

    void onExportCsv() {
        exportCsv();
    }

    void onExportSql() {
        exportSql();
    }

    /**
     * To be executed in EDT
     */
    private void refresh() {
        refreshDataTask.forceRefresh();
    }

    private void statementSelected(Long logId) {
        String txt1 = "";
        String txt2 = "";
        if (logId != null) {
            final StatementLog statementLog = logRepository.getStatementLog(logId);

            switch (groupBy) {
            case NONE:
                txt1 = statementLog.getRawSql();
                switch (statementLog.getStatementType()) {
                case NON_PREPARED_BATCH_EXECUTION:
                    txt1 = logExporter.getBatchedExecutions(statementLog);
                    break;
                case PREPARED_BATCH_EXECUTION:
                    txt2 = logExporter.getBatchedExecutions(statementLog);
                    break;
                case BASE_PREPARED_STMT:
                case PREPARED_QUERY_STMT:
                    txt2 = statementLog.getFilledSql();
                    break;
                default:
                    break;
                }
                break;
            case RAW_SQL:
                switch (statementLog.getStatementType()) {
                case BASE_NON_PREPARED_STMT:
                case BASE_PREPARED_STMT:
                case PREPARED_BATCH_EXECUTION:
                case PREPARED_QUERY_STMT:
                case NON_PREPARED_QUERY_STMT:
                    txt1 = statementLog.getRawSql();
                    break;
                case NON_PREPARED_BATCH_EXECUTION:
                    txt1 = "Cannot display details in \"Group by\" modes";
                }
                break;
            case FILLED_SQL:
                switch (statementLog.getStatementType()) {
                case BASE_NON_PREPARED_STMT:
                case PREPARED_BATCH_EXECUTION:
                case NON_PREPARED_QUERY_STMT:
                    txt1 = statementLog.getRawSql();
                    break;
                case BASE_PREPARED_STMT:
                case PREPARED_QUERY_STMT:
                    txt1 = statementLog.getRawSql();
                    txt2 = statementLog.getFilledSql();
                    break;
                case NON_PREPARED_BATCH_EXECUTION:
                    txt1 = "Cannot display details in \"Group by\" modes";
                }
                break;
            }

            if (statementLog.getSqlException() != null) {
                final CharArrayWriter writer = new CharArrayWriter();
                statementLog.getSqlException().printStackTrace(new PrintWriter(writer));
                txt2 += writer.toString();
            }
        }
        perfLoggerPanel.txtFieldSqlDetail1.setText(txt1);
        perfLoggerPanel.txtFieldSqlDetail1.select(0, 0);
        perfLoggerPanel.txtFieldSqlDetail2.setText(txt2);
        perfLoggerPanel.txtFieldSqlDetail2.select(0, 0);
        // scrollPaneSqlDetail1.setEnabled(txt1 != null);
        // scrollPaneSqlDetail2.setEnabled(txt2 != null);
    }

    private void exportSql() {
        final JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("SQL file", "sql"));
        if (fileChooser.showSaveDialog(perfLoggerPanel) == JFileChooser.APPROVE_OPTION) {
            File targetFile = fileChooser.getSelectedFile();
            if (!targetFile.getName().toLowerCase().endsWith(".sql")) {
                targetFile = new File(targetFile.getAbsolutePath() + ".sql");
            }
            selectAllLogStatements.doSelect(logExporter.getSqlLogExporter(targetFile));
        }
    }

    private void exportCsv() {
        final JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV file", "csv"));
        if (fileChooser.showSaveDialog(perfLoggerPanel) == JFileChooser.APPROVE_OPTION) {
            File targetFile = fileChooser.getSelectedFile();
            if (!targetFile.getName().toLowerCase().endsWith(".csv")) {
                targetFile = new File(targetFile.getAbsolutePath() + ".csv");
            }
            selectAllLogStatements.doSelect(logExporter.getCsvLogExporter(targetFile));
        }
    }

    /**
     * A {@link TimerTask} that regularly polls the associated {@link LogRepository} to check for new statements to
     * display. If the UI must be refreshed it is later done in the EDT.
     * 
     * @author slaurent
     * 
     */
    private class RefreshDataTask extends TimerTask {
        private volatile long lastRefreshTime;
        private int connectionsCount;

        @Override
        public void run() {
            if (logRepository.getLastModificationTime() <= lastRefreshTime
                    && connectionsCount == logReceiver.getConnectionsCount()) {
                return;
            }
            connectionsCount = logReceiver.getConnectionsCount();

            lastRefreshTime = logRepository.getLastModificationTime();
            doRefreshData(currentSelectLogRunner);

            final StringBuilder txt = new StringBuilder();
            if (logReceiver.isServerMode()) {
                txt.append(connectionsCount);
                txt.append(" connection(s) - ");
            }
            txt.append(logRepository.countStatements());
            txt.append(" statements logged - ");
            txt.append(TimeUnit.NANOSECONDS.toMillis(logRepository.getTotalExecAndFetchTimeNanos()));
            txt.append("ms total execution time (with fetch)");
            if ((txtFilter != null && txtFilter.length() > 0)
                    || (minDurationNanos != null && minDurationNanos.longValue() > 0)) {
                txt.append(" - ");
                txt.append(TimeUnit.NANOSECONDS.toMillis(logRepository.getTotalExecAndFetchTimeNanos(txtFilter,
                        minDurationNanos)));
                txt.append("ms total filtered");
            }
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    perfLoggerPanel.lblStatus.setText(txt.toString());
                }
            });
        }

        void forceRefresh() {
            lastRefreshTime = -1L;
        }

        void doRefreshData(SelectLogRunner selectLogRunner) {
            selectLogRunner.doSelect(new ResultSetAnalyzer() {
                @Override
                public void analyze(ResultSet resultSet) throws SQLException {
                    final ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
                    final int columnCount = resultSetMetaData.getColumnCount();

                    final List<String> tempColumnNames = new ArrayList<String>();
                    final List<Class<?>> tempColumnTypes = new ArrayList<Class<?>>();
                    final List<Object[]> tempRows = new ArrayList<Object[]>();
                    try {
                        for (int i = 1; i <= columnCount; i++) {
                            tempColumnNames.add(resultSetMetaData.getColumnLabel(i).toUpperCase());
                            if (resultSetMetaData.getColumnType(i) == Types.TIMESTAMP) {
                                tempColumnTypes.add(String.class);
                            } else {
                                tempColumnTypes.add(Class.forName(resultSetMetaData.getColumnClassName(i)));
                            }
                        }

                        final SimpleDateFormat tstampFormat = new SimpleDateFormat(/* "yyyy-MM-dd "+ */"HH:mm:ss.SSS");
                        while (resultSet.next()) {
                            final Object[] row = new Object[columnCount];
                            for (int i = 1; i <= columnCount; i++) {
                                row[i - 1] = resultSet.getObject(i);
                                if (row[i - 1] instanceof Timestamp) {
                                    row[i - 1] = tstampFormat.format(row[i - 1]);
                                }
                            }
                            tempRows.add(row);
                        }
                    } catch (final ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    }

                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            perfLoggerPanel.setData(tempRows, tempColumnNames, tempColumnTypes, tableStructureChanged);
                            tableStructureChanged = false;
                        }
                    });
                }
            });
        }

    }

    enum GroupBy {
        NONE, RAW_SQL, FILLED_SQL;
    }
}
