package dev.theredja.src2mc.client.menu;

import dev.theredja.src2mc.menu.KvTree;
import java.util.ArrayList;
import java.util.List;

/**
 * Our own confirmation dialog, made from INFRA's {@code infra_menu_dialog.res} (background panel, centred
 * {@code MenuLargeLight} message, item list): the message text replaced, the list given a confirm and a back button, and
 * the controls shifted to the middle of a panel of another size than the dialog's 890 x 490. INFRA itself has no such
 * dialog on its chapter screen; this is ours.
 */
final class ConfirmDialog {
    private ConfirmDialog() {}

    static final int DIALOG_WIDE = 890, DIALOG_TALL = 490;

    private static KvTree.Entry leaf(String key, String value) { return new KvTree.Entry(key, value, null); }

    private static KvTree.Entry block(String key, List<KvTree.Entry> entries) { return new KvTree.Entry(key, null, new KvTree(entries)); }

    private static KvTree.Entry button(String name, String label, String command, int position) {
        return block(name, List.of(leaf("ControlName", "CINFRAMenuItemButton"), leaf("labelText", label), leaf("command", command),
            leaf("listposition", Integer.toString(position))));
    }

    /**
     * @param dialog the parsed {@code infra_menu_dialog.res}
     * @param wide   the panel's pixel size the dialog is shown on
     */
    static KvTree build(KvTree dialog, int wide, int tall, String message, String confirmLabel, String confirmCommand,
                        String backLabel, String backCommand) {
        int dx = (wide - DIALOG_WIDE) / 2, dy = (tall - DIALOG_TALL) / 2;
        List<KvTree.Entry> top = new ArrayList<>();
        for (KvTree.Entry file : dialog.entries()) {
            if (!file.isBlock()) {
                top.add(file);
                continue;
            }
            List<KvTree.Entry> controls = new ArrayList<>();
            for (KvTree.Entry control : file.block().entries()) controls.add(control.isBlock() ? shifted(control, dx, dy, message, confirmLabel, confirmCommand, backLabel, backCommand) : control);
            top.add(block(file.key(), controls));
        }
        return new KvTree(top);
    }

    private static KvTree.Entry shifted(KvTree.Entry control, int dx, int dy, String message, String confirmLabel, String confirmCommand,
                                        String backLabel, String backCommand) {
        boolean list = "CINFRAMenuItemList".equalsIgnoreCase(control.block().get("ControlName"));
        boolean label = control.key().equalsIgnoreCase("message");
        List<KvTree.Entry> out = new ArrayList<>();
        for (KvTree.Entry entry : control.block().entries()) {
            if (entry.isBlock()) {
                if (!list) out.add(entry); // the dialog's own items are replaced below
                continue;
            }
            String key = entry.key().toLowerCase(java.util.Locale.ROOT);
            switch (key) {
                case "xpos" -> out.add(leaf(entry.key(), shift(entry.value(), dx)));
                case "ypos" -> out.add(leaf(entry.key(), shift(entry.value(), dy)));
                case "tall" -> out.add(list ? leaf(entry.key(), "80") : entry);
                case "labeltext" -> out.add(label ? leaf(entry.key(), message) : entry);
                default -> out.add(entry);
            }
        }
        if (list) {
            out.add(button("confirm_button", confirmLabel, confirmCommand, 0));
            out.add(button("back_button", backLabel, backCommand, 1));
        }
        return block(control.key(), out);
    }

    private static String shift(String value, int by) {
        try {
            return Integer.toString((int) Math.round(Double.parseDouble(value.trim())) + by);
        } catch (NumberFormatException exception) {
            return value;
        }
    }
}
