package kr.ac.aikopo.javatermproject;

import kr.ac.aikopo.javatermproject.ProductRepository.Manufacturer;
import kr.ac.aikopo.javatermproject.ProductRepository.Product;
import kr.ac.aikopo.javatermproject.ProductRepository.ProductInput;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class ProductManagementApp extends JFrame {
    private static final long MAX_PRICE = 999_999_999_999L;

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"상품번호", "상품명", "가격", "제조업체"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JTextField nameField = new JTextField(18);
    private final JTextField priceField = new JTextField(12);
    private final JComboBox<Manufacturer> manufacturerBox = new JComboBox<>();
    private final JButton insertButton = new JButton("등록");
    private final JButton refreshButton = new JButton("새로고침");
    private final JButton updateButton = new JButton("수정");
    private final JButton deleteButton = new JButton("삭제");
    private final JLabel status = new JLabel(" ");
    private List<Product> products = List.of();
    private boolean loading;

    private ProductManagementApp() {
        super("상품 관리");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(690, 360));

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        InventoryPanel inventory = new InventoryPanel();
        OrderPanel orders = new OrderPanel();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("상품 관리", content);
        tabs.addTab("재고 관리", inventory);
        tabs.addTab("주문 관리", orders);
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedComponent() == inventory) {
                inventory.refresh();
            } else if (tabs.getSelectedComponent() == orders) {
                orders.refresh();
            }
        });
        setContentPane(tabs);

        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEADING));
        addField(fields, "상품명", nameField);
        addField(fields, "가격", priceField);
        addField(fields, "제조업체", manufacturerBox);
        content.add(fields, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setPreferredScrollableViewportSize(new Dimension(780, 320));
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showSelection();
            }
        });
        content.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING));
        buttons.add(insertButton);
        buttons.add(refreshButton);
        buttons.add(updateButton);
        buttons.add(deleteButton);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(buttons, BorderLayout.NORTH);
        footer.add(status, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);

        insertButton.addActionListener(event -> saveProduct(false));
        refreshButton.addActionListener(event -> refreshProducts());
        updateButton.addActionListener(event -> saveProduct(true));
        deleteButton.addActionListener(event -> deleteProduct());

        pack();
        setLocationRelativeTo(null);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ProductManagementApp app = new ProductManagementApp();
            app.setVisible(true);
            app.refreshProducts();
        });
    }

    private void addField(JPanel panel, String name, JComponent field) {
        JLabel label = new JLabel(name);
        label.setLabelFor(field);
        panel.add(label);
        panel.add(field);
    }

    private Product selectedProduct() {
        int row = table.getSelectedRow();
        return row < 0 ? null : products.get(row);
    }

    private void showSelection() {
        Product product = selectedProduct();
        if (product != null) {
            nameField.setText(product.name());
            priceField.setText(Long.toString(product.price()));
            manufacturerBox.setSelectedItem(product.manufacturer());
        }
        updateControls();
    }

    private void updateControls() {
        table.setEnabled(!loading);
        nameField.setEnabled(!loading);
        priceField.setEnabled(!loading);
        manufacturerBox.setEnabled(!loading);
        refreshButton.setEnabled(!loading);
        insertButton.setEnabled(!loading && manufacturerBox.getItemCount() > 0);
        boolean canEdit = !loading && selectedProduct() != null;
        updateButton.setEnabled(canEdit);
        deleteButton.setEnabled(canEdit);
    }

    private ProductInput readInput() {
        String name = nameField.getText().strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 150) {
            throw new IllegalArgumentException("상품명을 1~150자로 입력하세요.");
        }

        long price;
        try {
            String text = priceField.getText().strip();
            if (!text.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            price = Long.parseLong(text);
            if (price > MAX_PRICE) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("가격을 0~999,999,999,999 사이의 정수로 입력하세요.");
        }

        Manufacturer manufacturer = (Manufacturer) manufacturerBox.getSelectedItem();
        if (manufacturer == null) {
            throw new IllegalArgumentException("제조업체를 선택하세요.");
        }
        return new ProductInput(name, price, manufacturer.id());
    }

    private void refreshProducts() {
        Product product = selectedProduct();
        Selection selection = new Selection(product == null ? null : product.id(), "상품 목록을 조회했습니다.");
        runQuery(repository -> selection, false);
    }

    private void saveProduct(boolean updating) {
        ProductInput input;
        try {
            input = readInput();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "입력 확인", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (updating) {
            long id = selectedProduct().id();
            runQuery(repository -> repository.update(id, input)
                    ? new Selection(id, "상품을 수정했습니다.")
                    : new Selection(null, "선택한 상품이 이미 삭제되었습니다."), true);
        } else {
            runQuery(repository -> new Selection(repository.insert(input), "상품을 등록했습니다."), true);
        }
    }

    private void deleteProduct() {
        Product product = selectedProduct();
        int answer = JOptionPane.showConfirmDialog(this,
                "상품번호 " + product.id() + " (" + product.name() + ") 상품을 삭제할까요?",
                "상품 삭제", JOptionPane.YES_NO_OPTION);
        if (answer == JOptionPane.YES_OPTION) {
            runQuery(repository -> repository.delete(product.id())
                    ? new Selection(null, "상품을 삭제했습니다.")
                    : new Selection(null, "선택한 상품이 이미 삭제되었습니다."), true);
        }
    }

    private void runQuery(ProductAction action, boolean writing) {
        loading = true;
        updateControls();
        status.setText("Oracle에서 처리 중입니다.");

        new SwingWorker<ProductList, Void>() {
            @Override
            protected ProductList doInBackground() throws SQLException {
                try (var connection = Database.open()) {
                    if (writing) {
                        connection.setAutoCommit(false);
                    }
                    try {
                        var repository = new ProductRepository(connection);
                        Selection selection = action.execute(repository);
                        ProductList result = new ProductList(repository.findProducts(),
                                repository.findManufacturers(), selection);
                        if (writing) {
                            connection.commit();
                        }
                        return result;
                    } catch (SQLException | RuntimeException e) {
                        if (writing) {
                            try {
                                connection.rollback();
                            } catch (SQLException rollbackFailure) {
                                e.addSuppressed(rollbackFailure);
                            }
                        }
                        throw e;
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    displayProducts(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status.setText("상품 작업이 중단되었습니다.");
                } catch (ExecutionException e) {
                    showError(e.getCause());
                } finally {
                    loading = false;
                    updateControls();
                }
            }
        }.execute();
    }

    private void displayProducts(ProductList result) {
        table.clearSelection();
        tableModel.setRowCount(0);
        products = result.products();
        manufacturerBox.setModel(new DefaultComboBoxModel<>(result.manufacturers().toArray(Manufacturer[]::new)));
        nameField.setText("");
        priceField.setText("");

        int selectedRow = -1;
        for (Product product : products) {
            if (Long.valueOf(product.id()).equals(result.selection().id())) {
                selectedRow = tableModel.getRowCount();
            }
            tableModel.addRow(new Object[]{product.id(), product.name(), product.price(), product.manufacturer().name()});
        }
        if (selectedRow >= 0) {
            table.setRowSelectionInterval(selectedRow, selectedRow);
            table.scrollRectToVisible(table.getCellRect(selectedRow, 0, true));
        }
        status.setText(result.selection().message() + "  상품 " + products.size() + "건");
    }

    private void showError(Throwable error) {
        String message;
        if (error instanceof IllegalStateException) {
            message = error.getMessage();
        } else if (error instanceof SQLException sqlError) {
            message = switch (sqlError.getErrorCode()) {
                case 2292 -> "이 상품을 참조하는 상품옵션이 있어 삭제할 수 없습니다.";
                case 2291 -> "선택한 제조업체가 없습니다. 새로고침한 뒤 다시 선택하세요.";
                default -> "Oracle 작업에 실패했습니다. 오류 코드 " + sqlError.getErrorCode();
            };
        } else {
            message = "상품 작업 중 오류가 발생했습니다.";
        }
        status.setText(message);
        JOptionPane.showMessageDialog(this, message, "상품 관리", JOptionPane.ERROR_MESSAGE);
    }

    @FunctionalInterface
    private interface ProductAction {
        Selection execute(ProductRepository repository) throws SQLException;
    }

    private record Selection(Long id, String message) {
    }

    private record ProductList(List<Product> products, List<Manufacturer> manufacturers, Selection selection) {
    }
}
