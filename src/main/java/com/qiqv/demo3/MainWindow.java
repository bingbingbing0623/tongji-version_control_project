package com.qiqv.demo3;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.qiqv.demo3.HighLight.highlightDifferences;

public class MainWindow
{
    // 显示保存的内容在 UI 界面，使用 IntelliJ 的 Editor 组件
    public void showSavedContentUI(VirtualFile rootDirectory, Project project) {
        // 创建 JFrame 作为主窗口
        JFrame frame = new JFrame("Saved File Content");
        frame.setSize(1200, 600); // 设置窗口大小
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new GridLayout(1, 3)); // 将布局改为 1 行 3 列

        // 创建 JTree 显示项目文件
        VirtualFile SnapShot=findFolder(rootDirectory,"snapshot");
        JTree fileTree = createFileTree(SnapShot);
        // 设置 JTree 的字体大小
        Font treeFont = new Font("Arial", Font.PLAIN, 18); // 增加字体大小
        fileTree.setFont(treeFont);

        // 增加行高，使文件夹图标和文本更大
        fileTree.setRowHeight(30); // 设置较高的行高
        frame.add(new JScrollPane(fileTree)); // 添加文件树到第一列

        // 创建 JTextArea 显示文件内容（第二列）
        JTextArea textArea1 = new JTextArea();
        textArea1.setEditable(false); // 设置为只读
        frame.add(new JScrollPane(textArea1)); // 添加到第二列

        // 创建新的 JTextArea 作为第三列
        JTextArea textArea2 = new JTextArea();
        textArea2.setEditable(false); // 设置为只读
        frame.add(new JScrollPane(textArea2)); // 添加到第三列

        // 获取 IntelliJ 的配色方案
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        Font font = scheme.getFont(EditorFontType.PLAIN); // 获取默认字体

        // 设置第二列 JTextArea 的字体和颜色
        textArea1.setFont(font);
        textArea1.setForeground(scheme.getDefaultForeground()); // 设置前景色
        textArea1.setBackground(scheme.getDefaultBackground()); // 设置背景色

        // 设置第三列 JTextArea 的字体和颜色（保持一致）
        textArea2.setFont(font);
        textArea2.setForeground(scheme.getDefaultForeground());
        textArea2.setBackground(scheme.getDefaultBackground());

        // 添加树选择监听器
        fileTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (selectedNode != null && selectedNode.getUserObject() instanceof VirtualFile) {
                VirtualFile selectedFile = (VirtualFile) selectedNode.getUserObject();
                // 解析了 .diff 文件获取了路径
                String diffFilePath = selectedFile.getPath();
                // 合并文件内容
                java.util.List<String> newContentLine = showFileContent(diffFilePath,textArea1,1);
                //当前内容
                java.util.List<String> targetContentLine = showFileContent(diffFilePath,textArea2,2);
                generateNewDiffForDisplay(newContentLine, targetContentLine, project);
                // 读取 diff 文件并解析差异
                // 找到 compare 文件夹下的 difference.diff 文件并解析
                VirtualFile projectRoot = project.getBaseDir();  // 获取项目的根目录
                File compareDir = new File(projectRoot.getPath() + "/compare");  // 动态创建 'compare' 文件夹路径
                // 生成 difference.diff 文件的完整路径
                File difference = new File(compareDir, "difference.diff");
                ShowDiff.Diff diff = ShowDiff.parseDiffFile(difference.getPath());
                java.util.List<String> diffLinesCompare =diff.diffLines;
                // 高亮显示文件内容的差异
                try {
                    highlightDifferences(newContentLine, targetContentLine, diffLinesCompare, textArea1, textArea2);
                } catch (BadLocationException ex) {
                    throw new RuntimeException(ex);
                }


            }
        });
        // 创建右键菜单
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem recoverMenuItem = new JMenuItem("恢复文件");
        popupMenu.add(recoverMenuItem);

        // 添加鼠标监听器，用于检测右键点击
        fileTree.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                // 检测是否是右键点击
                if (SwingUtilities.isRightMouseButton(e)) {
                    // 获取点击的节点
                    TreePath path = fileTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        fileTree.setSelectionPath(path);
                        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();

                        // 确保选择的节点是 VirtualFile 对象
                        if (selectedNode != null && selectedNode.getUserObject() instanceof VirtualFile) {
                            VirtualFile selectedFile1 = (VirtualFile) selectedNode.getUserObject();

                            // 在鼠标位置显示右键菜单
                            popupMenu.show(fileTree, e.getX(), e.getY());

                            // 给“恢复文件”菜单项添加事件监听器
                            recoverMenuItem.addActionListener(event -> {
                                // 解析了 .diff 文件获取了路径
                                String diffFilePath = selectedFile1.getPath();
                                // 合并文件内容
                                ShowDiff.Diff diff = ShowDiff.parseDiffFile(diffFilePath);
                                String originalContent = ShowDiff.readFileContent(diff.originalFilePath);
                                String newContent = ShowDiff.applyDiff(originalContent, diff.diffLines);
                                List<String> newContentLine2 = Arrays.asList(newContent.split("\n"));

                                // 打印或处理 selectedFile 对象
                                System.out.println("选中的文件: " + selectedFile1.getPath()+"wenjianlujing"+diff.targetFilePath);

                                // 执行文件恢复操作
                                Recover.writeFile(newContentLine2, diff.targetFilePath);
                            });
                        }
                    }
                }
            }
        });


        // 显示窗口
        frame.setVisible(true);
    }
    public VirtualFile findFolder(VirtualFile rootDirectory,String name) {
        // 遍历 rootDirectory 的一级子目录
        for (VirtualFile child : rootDirectory.getChildren()) {
            // 检查是否是目录并且名字是 "snapshot"
            if (child.isDirectory() && child.getName().equals(name)) {
                // 返回 snapshot 文件夹的 VirtualFile 对象
                return child;
            }
        }
        // 如果没有找到 snapshot 文件夹，返回 null
        return null;
    }


    // 创建项目文件的 JTree
    private JTree createFileTree(VirtualFile rootDirectory) {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(rootDirectory.getName());
        createTreeNodes(rootDirectory, rootNode);

        DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
        JTree fileTree = new JTree(treeModel);
        fileTree.setRootVisible(false); // 隐藏根节点
        return fileTree;
    }

    // 递归创建 JTree 的节点
    private void createTreeNodes(VirtualFile directory, DefaultMutableTreeNode parentNode) {
        for (VirtualFile file : directory.getChildren()) {
            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(file) {
                @Override
                public String toString() {
                    return file.getName();  // 只显示文件名
                }
            };
            parentNode.add(childNode);

            if (file.isDirectory()) {
                createTreeNodes(file, childNode); // 如果是目录，继续递归
            }
        }
    }

    //传出来两个文件的包含每一行内容的List
    public List<String> showFileContent(String diffFilePath,  JTextArea textArea,int textAreaNum) {
        // 解析 .diff 文件
        ShowDiff.Diff diff = ShowDiff.parseDiffFile(diffFilePath);
        // 读取初始文件内容
        String originalContent = ShowDiff.readFileContent(diff.originalFilePath);
        String targetContent = ShowDiff.readFileContent(diff.targetFilePath);
        if (originalContent == null) {
            return null;
        }
        if (textAreaNum == 1) {
            // 应用 .diff 差异，生成新文件内容
            String newContent = ShowDiff.applyDiff(originalContent, diff.diffLines);
            List<String> newContentLine = Arrays.asList(newContent.split("\n"));
            textArea.setText(newContent);
            // 显示最终的合成文件内容
            return newContentLine;
        }
        else {
            List<String> targetContentLine = Arrays.asList(targetContent.split("\n"));
            textArea.setText(targetContent);


            return targetContentLine;
        }
    }//sh

    // 生成新的 .diff 文件
    public void generateNewDiffForDisplay(List<String> newContent, List<String> targetContent, Project project) {
        // 获得当前版本和 base 版本的 diff 内容
        System.out.printf("###进入 generateNewDiffForDisplay 方法\n");
        Patch<String> patch = DiffUtils.diff(newContent, targetContent);
        System.out.printf("###对比两个不同的 list 结束\n");

        // 创建相应的文件夹，并将 diff 文件写入文件夹中
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                System.out.printf("###进入 try\n");

                // 获取项目的根目录（通过 IntelliJ 项目对象）
                VirtualFile projectRoot = project.getBaseDir();  // 获取项目的根目录
                File compareDir = new File(projectRoot.getPath() + "/compare");  // 动态创建 'compare' 文件夹路径

                // 检查 'compare' 文件夹是否存在
                if (!compareDir.exists()) {
                    // 如果文件夹不存在，则创建它
                    compareDir.mkdir();  // 创建文件夹
                    System.out.println("### 'compare' 文件夹已创建: " + compareDir.getPath());
                } else {
                    System.out.println("### 'compare' 文件夹已存在: " + compareDir.getPath());
                }

                // 使用 DiffUtils 来生成统一 diff
                List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                        "originalFileName",  // 原始文件名
                        "currentFileName",   // 当前文件名
                        newContent,          // 原始文件内容
                        patch,               // Patch 对象
                        1                    // 上下文行数，一般为 3
                );

                // 将生成的 diff 列表合并为单个字符串
                String diffContentForDisplay = String.join("\n", unifiedDiff);
                System.out.printf("###生成了 diff 文件\n");

                // 将差异文件写入 'compare' 文件夹中的 difference.diff 文件
                File diffFile = new File(compareDir, "difference.diff");
                FileUtil.writeToFile(diffFile, diffContentForDisplay);
                System.out.println("### diff 文件已写入: " + diffFile.getPath());

            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}