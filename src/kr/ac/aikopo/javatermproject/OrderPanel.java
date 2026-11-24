package kr.ac.aikopo.javatermproject;

import kr.ac.aikopo.javatermproject.InventoryRepository.StockOption;
import kr.ac.aikopo.javatermproject.OrderRepository.Member;
import kr.ac.aikopo.javatermproject.OrderRepository.OrderDetail;
import kr.ac.aikopo.javatermproject.OrderRepository.OrderSummary;
import kr.ac.aikopo.javatermproject.OrderRepository.StockUnavailableException;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
import java.awt.GridLayout;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

final class OrderPanel extends JPanel {
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX");

    private final JComboBox<Member> memberBox = new JComboBox<>();
    private final JComboBox<OptionChoice> optionBox = new JComboBox<>();
    private final JTextField quantityField = new JTextField(7);
    private final JButton addButton = new JButton("옵션 담기");
    private final JButton removeButton = new JButton("선택 제거");
    private final JButton submitButton = new JButton("주문 등록");
    private final JButton refreshButton = new JButton("새로고침");
    private final JTable cartTable = table("옵션번호", "상품명", "색상", "사이즈", "수량");
    private final JTable orderTable = table("주문번호", "회원", "주문 일시", "합계 금액");
    private final JTable detailTable = table("상품명", "색상", "사이즈", "수량", "주문 당시 단가");
    private final JLabel status = new JLabel(" ");
    private final Map<Long, CartLine> cart = new LinkedHashMap<>();
    private boolean busy;
    private boolean displayingOrders;

    OrderPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        optionBox.setPrototypeDisplayValue(new OptionChoice(
                new StockOption(9999, "상품 옵션 선택", "BLACK", "L", 99999)));

        JPanel orderInputs = new JPanel(new FlowLayout(FlowLayout.LEADING));
        addField(orderInputs, "회원", memberBox);
        addField(orderInputs, "옵션", optionBox);
        addField(orderInputs, "수량", quantityField);
        orderInputs.add(addButton);
        orderInputs.add(removeButton);

        JPanel draft = new JPanel(new BorderLayout());
        draft.add(orderInputs, BorderLayout.NORTH);
        draft.add(tableSection("담은 옵션", cartTable, 82), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        actions.add(submitButton);
        actions.add(refreshButton);
        actions.add(new JLabel("표시 재고는 참고값입니다."));
        draft.add(actions, BorderLayout.SOUTH);
        add(draft, BorderLayout.NORTH);

        JPanel history = new JPanel(new GridLayout(2, 1, 0, 6));
        history.add(tableSection("주문 목록", orderTable, 120));
        history.add(tableSection("주문 상세", detailTable, 115));
        add(history, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        addButton.addActionListener(event -> addOption());
        removeButton.addActionListener(event -> removeOption());
        submitButton.addActionListener(event -> registerOrder());
        refreshButton.addActionListener(event -> refresh());
        cartTable.getSelectionModel().addListSelectionListener(event -> updateControls());
        orderTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !displayingOrders && !busy) {
                showSelectedDetails();
            }
        });
        updateControls();
    }

    void refresh() {
        if (!busy) {
            refresh(selectedId(orderTable), null);
        }
    }

    private void refresh(Long orderId, String registeredMessage) {
        execute(connection -> {
            var repository = new OrderRepository(connection);
            return new OrderView(repository.findMembers(), new InventoryRepository(connection).findOptions(),
                    repository.findOrders(), findDetails(repository, orderId), orderId);
        }, view -> {
            displayView(view);
            status.setText(registeredMessage == null
                    ? "주문 목록을 조회했습니다. 주문 " + view.orders().size() + "건"
                    : registeredMessage);
        }, registeredMessage == null ? "주문 목록 조회" : registeredMessage + " 목록 조회");
    }

    private List<OrderDetail> findDetails(OrderRepository repository, Long orderId) throws SQLException {
        return orderId == null ? List.of() : repository.findDetails(orderId);
    }

    private void showSelectedDetails() {
        Long orderId = selectedId(orderTable);
        displayDetails(List.of());
        if (orderId != null) {
            execute(connection -> new OrderRepository(connection).findDetails(orderId), details -> {
                displayDetails(details);
                status.setText("주문 " + orderId + "의 상세 " + details.size() + "건을 조회했습니다.");
            }, "주문 상세 조회");
        }
    }

    private void addOption() {
        OptionChoice choice = (OptionChoice) optionBox.getSelectedItem();
        if (choice == null) {
            warn("주문할 옵션을 선택하세요.");
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
            warn("주문 수량을 1~9,999,999,999 사이의 정수로 입력하세요.");
            return;
        }

        StockOption option = choice.option();
        CartLine previous = cart.get(option.id());
        long combined = previous == null ? quantity : previous.quantity() + quantity;
        if (combined > InventoryRepository.MAX_QUANTITY) {
            warn("같은 옵션의 합계 수량이 9,999,999,999개를 초과합니다.");
            return;
        }
        cart.put(option.id(), new CartLine(option, combined));
        quantityField.setText("");
        displayCart();
    }

    private void removeOption() {
        Long optionId = selectedId(cartTable);
        if (optionId != null) {
            cart.remove(optionId);
            displayCart();
        }
    }

    private void registerOrder() {
        if (cart.isEmpty()) {
            warn("주문할 옵션을 담으세요.");
            return;
        }

        Member member = (Member) memberBox.getSelectedItem();
        if (member == null) {
            warn("주문할 회원을 선택하세요.");
            return;
        }

        Map<Long, Long> quantities = new LinkedHashMap<>();
        cart.forEach((id, line) -> quantities.put(id, line.quantity()));
        execute(connection -> new OrderRepository(connection).placeOrder(member.id(), quantities), orderId -> {
            cart.clear();
            displayCart();
            refresh(orderId, "주문 " + orderId + "번을 등록했습니다.");
        }, "주문 등록");
    }

    private <T> void execute(OrderQuery<T> query, Consumer<T> onSuccess, String action) {
        busy = true;
        updateControls();
        status.setText("Oracle에서 처리 중입니다.");

        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws SQLException {
                try (var connection = Database.open()) {
                    return query.execute(connection);
                }
            }

            @Override
            protected void done() {
                busy = false;
                updateControls();
                try {
                    onSuccess.accept(get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showError(action + " 중 작업이 중단되었습니다.");
                } catch (ExecutionException error) {
                    showError(errorMessage(action, error.getCause()));
                }
            }
        }.execute();
    }

    private String errorMessage(String action, Throwable error) {
        if (error instanceof StockUnavailableException || error instanceof IllegalStateException) {
            return error.getMessage();
        }
        if (error instanceof SQLException sqlError) {
            if (sqlError.getErrorCode() == 2291) {
                return action + "에 실패했습니다. 회원과 옵션을 새로고침한 뒤 확인하세요.";
            }
            return action + "에 실패했습니다. Oracle 오류 코드 " + sqlError.getErrorCode();
        }
        return action + " 중 오류가 발생했습니다.";
    }

    private void displayView(OrderView view) {
        Member member = (Member) memberBox.getSelectedItem();
        OptionChoice option = (OptionChoice) optionBox.getSelectedItem();
        memberBox.setModel(new DefaultComboBoxModel<>(view.members().toArray(Member[]::new)));
        if (view.members().contains(member)) {
            memberBox.setSelectedItem(member);
        }
        optionBox.setModel(new DefaultComboBoxModel<>(
                view.options().stream().map(OptionChoice::new).toArray(OptionChoice[]::new)));
        optionBox.setSelectedIndex(-1);
        if (option != null) {
            for (int i = 0; i < optionBox.getItemCount(); i++) {
                if (optionBox.getItemAt(i).option().id() == option.option().id()) {
                    optionBox.setSelectedIndex(i);
                    break;
                }
            }
        }

        displayingOrders = true;
        try {
            DefaultTableModel model = model(orderTable);
            orderTable.clearSelection();
            model.setRowCount(0);
            for (OrderSummary order : view.orders()) {
                model.addRow(new Object[]{order.id(), order.memberName(), ORDER_TIME.format(order.orderedAt()),
                        order.total().toPlainString()});
                if (Long.valueOf(order.id()).equals(view.selectedId())) {
                    int row = model.getRowCount() - 1;
                    orderTable.setRowSelectionInterval(row, row);
                    orderTable.scrollRectToVisible(orderTable.getCellRect(row, 0, true));
                }
            }
        } finally {
            displayingOrders = false;
        }
        displayDetails(view.details());
    }

    private void displayCart() {
        var model = model(cartTable);
        model.setRowCount(0);
        for (CartLine line : cart.values()) {
            StockOption option = line.option();
            model.addRow(new Object[]{option.id(), option.productName(), option.color(), option.size(), line.quantity()});
        }
        updateControls();
    }

    private void displayDetails(List<OrderDetail> details) {
        var model = model(detailTable);
        model.setRowCount(0);
        for (OrderDetail detail : details) {
            model.addRow(new Object[]{detail.productName(), detail.color(), detail.size(),
                    detail.quantity(), detail.unitPrice()});
        }
    }

    private void updateControls() {
        memberBox.setEnabled(!busy);
        optionBox.setEnabled(!busy);
        quantityField.setEnabled(!busy);
        addButton.setEnabled(!busy);
        removeButton.setEnabled(!busy && cartTable.getSelectedRow() >= 0);
        submitButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        cartTable.setEnabled(!busy);
        orderTable.setEnabled(!busy);
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "입력 확인", JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String message) {
        status.setText(message);
        JOptionPane.showMessageDialog(this, message, "주문 관리", JOptionPane.ERROR_MESSAGE);
    }

    private static Long selectedId(JTable table) {
        int row = table.getSelectedRow();
        return row < 0 ? null : (Long) table.getValueAt(row, 0);
    }

    private static DefaultTableModel model(JTable table) {
        return (DefaultTableModel) table.getModel();
    }

    private static JTable table(String... columns) {
        JTable table = new JTable(new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        return table;
    }

    private static JScrollPane tableSection(String title, JTable table, int height) {
        table.setPreferredScrollableViewportSize(new Dimension(920, height));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder(title));
        return scroll;
    }

    private static void addField(JPanel panel, String title, JComponent field) {
        JLabel label = new JLabel(title);
        label.setLabelFor(field);
        panel.add(label);
        panel.add(field);
    }

    @FunctionalInterface
    private interface OrderQuery<T> {
        T execute(Connection connection) throws SQLException;
    }

    private record CartLine(StockOption option, long quantity) {
    }

    private record OptionChoice(StockOption option) {
        @Override
        public String toString() {
            return option.id() + "  " + option.productName() + " / " + option.color() + " / " + option.size()
                    + " (재고 " + option.quantity() + ")";
        }
    }

    private record OrderView(List<Member> members, List<StockOption> options, List<OrderSummary> orders,
                             List<OrderDetail> details, Long selectedId) {
    }
}
