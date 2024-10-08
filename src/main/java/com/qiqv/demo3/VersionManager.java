package com.qiqv.demo3;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;


import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.io.FileUtil;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class VersionManager {
    private final Project project;
    private final List<VirtualFile> savedFiles = new ArrayList<>(); // 用于保存项目中所有文件

    public VersionManager(Project project) {
        this.project = project;
    }

    // 点击插件时保存整个项目，并在 UI 中显示内容
    public void saveEntireProject() {
        VirtualFile projectRoot = project.getBaseDir(); // 获取项目根目录

        if (projectRoot != null) {
            saveAllFilesInDirectory(projectRoot); // 递归保存所有文件
            ApplicationManager.getApplication().runWriteAction(() -> createSnapshotFolder(projectRoot)); // 确保在写操作上下文中运行
            showSavedContentUI(projectRoot);  // 显示 UI
        } else {
            System.out.println("无法找到项目根目录");
        }
    }

    // 递归遍历并保存目录下的所有文件
    private void saveAllFilesInDirectory(VirtualFile directory) {
        for (VirtualFile file : directory.getChildren()) {
            if (!file.isDirectory() && !file.getName().equals("snapshot")) {
                savedFiles.add(file); // 保存文件
            } else if (file.isDirectory() && !file.getName().equals("snapshot")) {
                saveAllFilesInDirectory(file); // 如果是目录且不是 snapshot，递归遍历
            }
        }
    }

    // 在项目根目录下创建 snapshot 文件夹，并在其中创建一个以当前时间命名的文件夹保存当前文件
    private void createSnapshotFolder(VirtualFile rootDirectory) {
        try {
            // 检查 snapshot 文件夹是否存在，不存在则创建
            VirtualFile snapshotFolder = rootDirectory.findChild("snapshot");
            if (snapshotFolder == null) {
                snapshotFolder = rootDirectory.createChildDirectory(this, "snapshot");
            }

            // 以当前时间命名创建一个新文件夹
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            VirtualFile timeStampedFolder = snapshotFolder.createChildDirectory(this, timeStamp);

            // 复制根目录下的所有文件夹到新的时间文件夹中
            for (VirtualFile file : rootDirectory.getChildren()) {
                if (file.isDirectory() && !file.getName().equals("snapshot")) {
                    copyDirectory(file, timeStampedFolder);
                } else if (!file.isDirectory()) {
                    copyFile(file, timeStampedFolder);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("无法创建 snapshot 文件夹或复制文件");
        }
    }

    // 递归复制文件夹及其内容
    private void copyDirectory(VirtualFile source, VirtualFile targetDirectory) throws IOException {
        if (source.getName().equals("snapshot")) {
            return; // 跳过 snapshot 文件夹
        }

        VirtualFile newDir = targetDirectory.createChildDirectory(this, source.getName());
        for (VirtualFile child : source.getChildren()) {
            if (child.isDirectory()) {
                copyDirectory(child, newDir); // 递归复制子目录
            } else {
                copyFile(child, newDir); // 复制文件
            }
        }
    }

    // 复制单个文件
    private void copyFile(VirtualFile file, VirtualFile targetDirectory) throws IOException {
        VirtualFile newFile = targetDirectory.createChildData(this, file.getName());
        FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();
        Document document = fileDocumentManager.getDocument(file);

        if (document != null) {
            String content = document.getText();
            FileUtil.writeToFile(new java.io.File(newFile.getPath()), content);
        }
    }

    // 显示保存的内容在 UI 界面，使用 IntelliJ 的 Editor 组件
    private void showSavedContentUI(VirtualFile rootDirectory) {
        // 创建 JFrame 作为主窗口
        JFrame frame = new JFrame("Saved File Content");
        frame.setSize(800, 600); // 设置窗口大小
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // 创建 JTree 显示项目文件
        JTree fileTree = createFileTree(rootDirectory);
        frame.add(new JScrollPane(fileTree), BorderLayout.WEST);

        // 创建 JTextArea 显示文件内容
        JTextArea textArea = new JTextArea();
        textArea.setEditable(false); // 设置为只读
        frame.add(new JScrollPane(textArea), BorderLayout.CENTER);

        // 获取 IntelliJ 的配色方案
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        Font font = scheme.getFont(EditorFontType.PLAIN); // 获取默认字体
        textArea.setFont(font); // 设置字体
        textArea.setForeground(scheme.getDefaultForeground()); // 设置前景色
        textArea.setBackground(scheme.getDefaultBackground()); // 设置背景色

        // 添加树选择监听器
        fileTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (selectedNode != null && selectedNode.getUserObject() instanceof VirtualFile) {
                VirtualFile selectedFile = (VirtualFile) selectedNode.getUserObject();
                loadFileContent(selectedFile, textArea); // 加载文件内容到文本区域
            }
        });

        // 显示窗口
        frame.setVisible(true);
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

    // 加载选中文件的内容
    private void loadFileContent(VirtualFile file, JTextArea textArea) {
        FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();
        Document document = fileDocumentManager.getDocument(file);

        if (document != null) {
            textArea.setText(document.getText()); // 设置文本内容
        } else {
            textArea.setText("无法加载文件内容"); // 文件内容为空
        }
    }
}
