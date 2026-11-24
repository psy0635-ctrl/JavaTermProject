package kr.ac.aikopo.javatermproject;

import kr.ac.aikopo.javatermproject.InventoryRepository.StockOption;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutionException;

final class InventoryPanel extends JPanel {
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"옵션번호", "상품명", "색상", "사이즈", "현재 재고"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JLabel selection = new JLabel("선택한 옵션 없음");
    private final JTextField quantityField = new JTextField(12);
    private final JButton receiveButton = new JButton("입고");
    private final JButton refreshButton = new JButton("새로고침");
    private final JLabel status = new JLabel(" ");
    private boolean loading;

    InventoryPanel() {
        super(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
        JLabel quantityLabel = new JLabel("입고 수량");
        quantityLabel.setLabelFor(quantityField);
        controls.add(quantityLabel);
        controls.add(quantityField);
        controls.add(receiveButton);
        controls.add(refreshButton);
        controls.add(selection);
        add(controls, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setPreferredScrollableViewportSize(new Dimension(780, 320));
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                Long id = selectedOptionId();
                selection.setText(id == null ? "선택한 옵션 없음" : "선택한 옵션 " + id);
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        receiveButton.addActionListener(event -> receiveStock());
        refreshButton.addActionListener(event -> refresh());
    }

    void refresh() {
        if (!loading) {
            loadOptions(selectedOptionId(), null);
        }
    }

    private Long selectedOptionId() {
        int row = table.getSelectedRow();
        return row < 0 ? null : (Long) table.getValueAt(row, 0);
    }

    private void receiveStock() {
        Long optionId = selectedOptionId();
        if (optionId == null) {
            showInputWarning("입고할 옵션을 먼저 선택하세요.");
            return;
        }

        String text = quantityField.getText().strip();
        long quantity;
        try {
            if (!text.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            quantity = Long.parseLong(text);
            if (quantity < 1 || quantity > InventoryRepository.MAX_QUANTITY) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException error) {
            showInputWarning("입고 수량을 1~9,999,999,999 사이의 정수로 입력하세요.");
            return;
        }
        loadOptions(optionId, quantity);
    }

    private void showInputWarning(String message) {
        JOptionPane.showMessageDialog(this, message, "입력 확인", JOptionPane.WARNING_MESSAGE);
    }

    private void loadOptions(Long optionId, Long quantity) {
        setLoading(true);
        status.setText("Oracle에서 처리 중입니다.");
        new SwingWorker<List<StockOption>, Void>() {
            @Override
            protected List<StockOption> doInBackground() throws SQLException {
                try (var connection = Database.open()) {
                    var repository = new InventoryRepository(connection);
                    if (quantity == null) {
                        return repository.findOptions();
                    }

                    connection.setAutoCommit(false);
                    try {
                        repository.receive(optionId, quantity);
                        List<StockOption> options = repository.findOptions();
                        connection.commit();
                        return options;
                    } catch (SQLException | RuntimeException error) {
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackError) {
                            error.addSuppressed(rollbackError);
                        }
                        throw error;
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    List<StockOption> options = get();
                    displayOptions(options, optionId);
                    if (quantity != null) {
                        quantityField.setText("");
                        status.setText("옵션 " + optionId + "에 " + quantity + "개를 입고했습니다.");
                    } else {
                        status.setText("재고 목록을 조회했습니다.  옵션 " + options.size() + "건");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText("재고 작업이 중단되었습니다.");
                } catch (ExecutionException error) {
                    showError(error.getCause());
                } finally {
                    setLoading(false);
                }
            }
        }.execute();
    }

    private void setLoading(boolean busy) {
        loading = busy;
        table.setEnabled(!busy);
        quantityField.setEnabled(!busy);
        receiveButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
    }

    private void displayOptions(List<StockOption> options, Long selectedId) {
        table.clearSelection();
        model.setRowCount(0);
        int selectedRow = -1;
        for (StockOption option : options) {
            if (Long.valueOf(option.id()).equals(selectedId)) {
                selectedRow = model.getRowCount();
            }
            model.addRow(new Object[]{
                    option.id(), option.productName(), option.color(), option.size(), option.quantity()
            });
        }
        if (selectedRow >= 0) {
            table.setRowSelectionInterval(selectedRow, selectedRow);
            table.scrollRectToVisible(table.getCellRect(selectedRow, 0, true));
        }
    }

    private void showError(Throwable error) {
        String message = "재고 작업 중 오류가 발생했습니다.";
        if (error instanceof IllegalStateException) {
            message = error.getMessage();
        } else if (error instanceof SQLException sqlError) {
            if ("02000".equals(sqlError.getSQLState())) {
                message = "선택한 옵션이 없습니다. 목록을 새로고침하세요.";
            } else if (sqlError.getErrorCode() == 1438) {
                message = "입고 후 재고가 최대 수량(9,999,999,999개)을 초과합니다.";
            } else {
                message = "Oracle 재고 작업에 실패했습니다. 오류 코드 " + sqlError.getErrorCode();
            }
        }
        status.setText(message);
        JOptionPane.showMessageDialog(this, message, "재고 관리", JOptionPane.ERROR_MESSAGE);
    }
}
