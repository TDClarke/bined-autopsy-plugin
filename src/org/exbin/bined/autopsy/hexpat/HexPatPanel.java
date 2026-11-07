package org.exbin.bined.autopsy.hexpat;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.event.TreeModelListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

/**
 * Panel shown below the hex view: loads a .hexpat file, evaluates it on the current data
 * in a background thread and shows the resulting structure as a tree.
 */
public class HexPatPanel extends JPanel {

    private static final String PREF_LAST_PATTERN = "lastPattern";

    /** Connection to the surrounding viewer. */
    public interface Host {

        /**
         * @return a NEW data source for the displayed content (it is read on a worker
         * thread, so it must not share caches with the hex view), or null if no data.
         */
        @Nullable
        ByteSource createDataSource();

        /** The user picked a tree item. */
        void nodeSelected(PatternNode node);

        /** A new evaluation result (or null when cleared) is ready: repaint colours. */
        void resultChanged(@Nullable HexPattern.Result result);
    }

    private final Host host;
    private final Preferences preferences = Preferences.userNodeForPackage(HexPatPanel.class);
    private final JTree tree = new JTree(new NodeModel(new PatternNode("<none>", "none")));
    private final JTextArea messages = new JTextArea(3, 40);
    private final JLabel fileLabel = new JLabel("  no pattern loaded");
    private final JButton reloadButton = new JButton("Reload");
    private final JButton clearButton = new JButton("Clear");
    private File patternFile;
    private HexPattern.Result result;
    private int generation;
    private boolean adjusting;

    public HexPatPanel(Host host) {
        super(new BorderLayout());
        this.host = host;

        String last = preferences.get(PREF_LAST_PATTERN, null);
        if (last != null && new File(last).isFile()) {
            setPatternFile(new File(last));
        }

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton open = new JButton("Open .hexpat...");
        open.addActionListener(e -> chooseFile());
        reloadButton.addActionListener(e -> reload());
        clearButton.addActionListener(e -> {
            patternFile = null;
            fileLabel.setText("  no pattern loaded");
            preferences.remove(PREF_LAST_PATTERN);
            clear();
        });
        bar.add(open);
        bar.add(reloadButton);
        bar.add(clearButton);
        bar.add(fileLabel);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp,
                    boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
                if (v instanceof PatternNode) {
                    PatternNode n = (PatternNode) v;
                    setText(n.name + " : " + n.typeName + "   " + (n.display == null ? "" : n.display)
                            + "   @0x" + Long.toHexString(n.offset).toUpperCase() + " +" + n.size
                            + (n.comment != null ? "   // " + n.comment : ""));
                }
                return this;
            }
        });
        tree.addTreeSelectionListener(e -> {
            Object o = e.getPath() == null ? null : e.getPath().getLastPathComponent();
            if (!adjusting && o instanceof PatternNode) {
                this.host.nodeSelected((PatternNode) o);
            }
        });
        messages.setEditable(false);
        messages.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        messages.setText("Open a .hexpat file to see the file structure.");

        add(bar, BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(tree), new JScrollPane(messages));
        split.setResizeWeight(0.85);
        add(split, BorderLayout.CENTER);
        updateButtons();
    }

    private void setPatternFile(File f) {
        patternFile = f;
        fileLabel.setText("  " + f.getName());
        preferences.put(PREF_LAST_PATTERN, f.getAbsolutePath());
    }

    private void updateButtons() {
        reloadButton.setEnabled(patternFile != null);
        clearButton.setEnabled(patternFile != null);
    }

    private void chooseFile() {
        JFileChooser fc = new JFileChooser(patternFile != null ? patternFile.getParentFile() : null);
        fc.setFileFilter(new FileNameExtensionFilter("ImHex patterns (*.hexpat)", "hexpat"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setPatternFile(fc.getSelectedFile());
            updateButtons();
            reload();
        }
    }

    /** Drops the shown result (e.g. when the viewed file changes or is closed). */
    public void clear() {
        generation++;
        result = null;
        tree.setModel(new NodeModel(new PatternNode("<none>", "none")));
        host.resultChanged(null);
        updateButtons();
    }

    /** Re-reads the pattern file and evaluates it on the current data in the background. */
    public void reload() {
        updateButtons();
        final ByteSource data = host.createDataSource();
        if (patternFile == null || data == null) {
            return;
        }
        final String src;
        try {
            src = new String(Files.readAllBytes(patternFile.toPath()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            messages.setText("Cannot read " + patternFile + ": " + ex.getMessage());
            return;
        }
        final int myGeneration = ++generation;
        messages.setText("Evaluating " + patternFile.getName() + "...");
        new SwingWorker<HexPattern.Result, Void>() {
            @Override
            protected HexPattern.Result doInBackground() {
                return HexPattern.run(src, data);
            }

            @Override
            protected void done() {
                if (myGeneration != generation) {
                    return; // the viewed file changed meanwhile
                }
                try {
                    show(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    messages.setText("Pattern evaluation failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    private void show(HexPattern.Result r) {
        result = r;
        tree.setModel(new NodeModel(r.root));
        StringBuilder sb = new StringBuilder();
        for (String s : r.errors) {
            sb.append(s).append('\n');
        }
        for (String s : r.log) {
            sb.append(s).append('\n');
        }
        messages.setText(sb.length() == 0 ? "OK, " + r.leaves().length + " items" : sb.toString());
        messages.setCaretPosition(0);
        host.resultChanged(r);
    }

    /** Select the tree path for the item under a hex-view position (caret moved). */
    public void selectPosition(long pos) {
        if (result == null) {
            return;
        }
        List<PatternNode> path = result.pathAt(pos);
        if (!path.isEmpty()) {
            TreePath tp = new TreePath(path.toArray());
            adjusting = true;
            try {
                tree.setSelectionPath(tp);
                tree.scrollPathToVisible(tp);
            } finally {
                adjusting = false;
            }
        }
    }

    private static final class NodeModel implements TreeModel {

        private final PatternNode root;

        NodeModel(PatternNode root) {
            this.root = root;
        }

        @Override
        public Object getRoot() {
            return root;
        }

        @Override
        public Object getChild(Object p, int i) {
            return ((PatternNode) p).children.get(i);
        }

        @Override
        public int getChildCount(Object p) {
            return ((PatternNode) p).children.size();
        }

        @Override
        public boolean isLeaf(Object n) {
            return ((PatternNode) n).children.isEmpty();
        }

        @Override
        public int getIndexOfChild(Object p, Object c) {
            return ((PatternNode) p).children.indexOf(c);
        }

        @Override
        public void valueForPathChanged(TreePath path, Object v) {
        }

        @Override
        public void addTreeModelListener(TreeModelListener l) {
        }

        @Override
        public void removeTreeModelListener(TreeModelListener l) {
        }
    }
}
