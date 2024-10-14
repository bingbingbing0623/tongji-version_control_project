package com.qiqv.demo3;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;

// 这个类基本没改，添加了自动保存的两个方法，以及在saveEntireProject中去掉了ui显示（把ui显示单独放在ShowVersionHistory类了）
public class VersionManager {
    private final Project project;
    private final List<VirtualFile> savedFiles = new ArrayList<>(); // 用于保存项目中所有文件
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD); // 定时任务执行器
    private boolean isFirstSave = true; // 标记是否为首次保存
    private boolean isBaseSave = false; // 标记初始版本是否保存
    private  VirtualFile snapshotDirectory; // 记录snapshot文件夹位置
    private VirtualFile rootFileDirectory; // 记录根文件位置
    private String baseVersionDirectory; // 记录base版本位置

    public VersionManager(Project project) {
        this.project = project;
    }

    // 开始定时保存任务
    public void startAutoSave(long intervalInSeconds) {
        alarm.addRequest(this::saveEntireProject, intervalInSeconds * 1000); // 间隔以毫秒为单位
    }

    // 停止自动保存任务
    public void stopAutoSave() {
        alarm.cancelAllRequests(); // 取消所有定时任务
    }

    // 点击插件时保存整个项目，并在 UI 中显示内容
    public void saveEntireProject() {
        VirtualFile projectRoot = project.getBaseDir(); // 获取项目根目录
        rootFileDirectory = projectRoot;

        if (projectRoot != null) {
            // 在首次保存时直接保存所有文件
            if (isFirstSave) {
                saveAllFilesInDirectory(projectRoot); // 递归保存所有文件
                isFirstSave = false; // 更新标记为非首次保存
                ApplicationManager.getApplication().invokeLater(() -> {
                    ApplicationManager.getApplication().runWriteAction(() -> createSnapshotFolder(projectRoot)); // 在 EDT 中执行写操作
                });
            } else {
                // 后续保存时对比并生成差异文件
                generateDiffFiles(projectRoot);
            }

        } else {
            System.out.println("无法找到项目根目录");
        }
        // 再次调度下一次保存任务
        startAutoSave(10); // 继续下一个定时任务，间隔5秒
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
            snapshotDirectory = snapshotFolder;

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

    // 生成文件差异并保存为 unified diff 格式
    private void generateDiffFiles(VirtualFile rootDirectory) {
        try {
            // 遍历项目根目录下的所有文件和子目录
            for (VirtualFile file : rootDirectory.getChildren()) {
                // 对file进行判断，判断file是目录还是文件，并且不考虑.gitignore和.idea的变化
                if (file.isDirectory() && !file.getName().equals("snapshot") && !file.getName().equals(".gitignore")&& !file.getName().equals(".idea")) {
                    generateDiffFiles(file); // 递归遍历子目录
                } else if (!file.isDirectory() && !file.getName().equals("snapshot") && !file.getName().equals(".gitignore")&& !file.getName().equals(".idea")) {
                    // 将baseVersion保存
                    if(!isBaseSave) {
                        // 获取snapshot文件夹内的所有文件
                        VirtualFile[] snapshotFiles = snapshotDirectory.getChildren();
                        // 选择第一次打开保存的版本
                        VirtualFile lastSnapshotFile = snapshotFiles.length > 0 ? snapshotFiles[snapshotFiles.length - 1] : null;
                        baseVersionDirectory = lastSnapshotFile.getPath();
                        isBaseSave = true;
                    }
                        // 用子序列来确定相对位置，方便后面确定originalFilePath
                    String relativePath = file.getPath().substring(rootFileDirectory.getPath().length() + 1);
                    String originalFilePath = baseVersionDirectory + "/" + relativePath; // 原始文件路径
                    System.out.println("######origin"+originalFilePath);
                    String currentFilePath = file.getPath(); // 当前文件路径

                    if (Files.exists(Paths.get( originalFilePath))) {
                        List<String> originalLines = Files.readAllLines(Paths.get( originalFilePath));
                        List<String> currentLines = Files.readAllLines(Paths.get(currentFilePath));
                        // 获得当前版本和base版本的diff内容
                        Patch<String> patch = DiffUtils.diff(originalLines, currentLines);
                        if (!patch.getDeltas().isEmpty()) {
                            // 如果有变化，生成 unified diff 文件
                            String diffFileName = file.getName() + ".diff";
                            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                            // 创建相应的文件夹，并将diff文件写入文件夹当中
                            WriteCommandAction.runWriteCommandAction(project, () -> {
                                try {
                                    if (!snapshotDirectory.exists()) {
                                        System.out.println("######### snapshotDirectory 不存在: " + snapshotDirectory);
                                    } else {
                                        VirtualFile timeStampedFolder = snapshotDirectory.createChildDirectory(this, timeStamp);
                                        VirtualFile diffFile = timeStampedFolder.createChildData(this, diffFileName);
                                        String diffContent = generateDiff(originalFilePath, currentFilePath, originalLines, patch);
                                        System.out.println("######"+diffContent);
                                        FileUtil.writeToFile(new java.io.File(diffFile.getPath()), diffContent);
                                        System.out.println("生成差异文件: " + diffFile.getPath());
                                    }
                            } catch (IOException e) {
                                e.printStackTrace();
                                }
                            });
                        }
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("无法生成差异文件");
        }
    }


    // 生成 unified diff 格式的字符串
    private String generateDiff(String originalFileName,  String currentFileName, List<String> originalLines, Patch<String> patch) {
        // 设置 diff 文件的头信息，包含旧文件名和新文件名
        String diffHeader = originalFileName + " -> " + currentFileName;

        // 使用 DiffUtils 来生成统一 diff
        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                originalFileName,  // 原始文件名
                currentFileName,   // 当前文件名
                originalLines,     // 原始文件内容
                patch,             // Patch 对象
                1                  // 上下文行数，一般为 3
        );

        // 将生成的 diff 列表合并为单个字符串
        return String.join("\n", unifiedDiff);
    }


    // 显示保存的内容在 UI 界面，使用 IntelliJ 的 Editor 组件
    public void showSavedContentUI(VirtualFile rootDirectory) {
        // 创建 JFrame 作为主窗口
        JFrame frame = new JFrame("Saved File Content");
        frame.setSize(1200, 600); // 设置窗口大小
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new GridLayout(1, 3)); // 将布局改为 1 行 3 列

        // 创建 JTree 显示项目文件
        JTree fileTree = createFileTree(rootDirectory);
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
                // 合并文件内容并显示到第二列
                showFileContent(diffFilePath, textArea1, 1);
                //显示当前内容在第三列
                showFileContent(diffFilePath, textArea2, 2);

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

    //根据传进来的textAreaNum参数来决定显示，修改之后的文件，或者是当前文件
    public void showFileContent(String diffFilePath, JTextArea textArea, int textAreaNum) {
        // 解析 .diff 文件
        showdiff.Diff diff = showdiff.parseDiffFile(diffFilePath);
        // 读取初始文件内容
        String originalContent = showdiff.readFileContent(diff.originalFilePath);
        String targetContent = showdiff.readFileContent(diff.targetFilePath);
        if (originalContent == null) {
            textArea.setText("无法读取初始文件内容");
            return;
        }
        if (textAreaNum == 1) {
            // 应用 .diff 差异，生成新文件内容
            String newContent = showdiff.applyDiff(originalContent, diff.diffLines);
            // 显示最终的合成文件内容到第三列
            textArea.setText(newContent);
        }
        else {
            textArea.setText(targetContent);
        }



    }


}
