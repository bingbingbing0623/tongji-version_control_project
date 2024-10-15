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
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
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
                // 合并文件内容
                List<String> newContentLine = showFileContent(diffFilePath,textArea1,1);
                //当前内容
                List<String> targetContentLine = showFileContent(diffFilePath,textArea2,2);
                generateNewDiffForDisplay(newContentLine, targetContentLine);
                // 读取 diff 文件并解析差异
                showdiff.Diff diff = showdiff.parseDiffFile("C:/Users/lrbde/IdeaProjects/test1/src/difference.diff");
                List<String> diffLinesCompare =diff.diffLines;
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
                                showdiff.Diff diff = showdiff.parseDiffFile(diffFilePath);
                                String originalContent = showdiff.readFileContent(diff.originalFilePath);
                                String newContent = showdiff.applyDiff(originalContent, diff.diffLines);
                                List<String> newContentLine2 = Arrays.asList(newContent.split("\n"));

                                // 打印或处理 selectedFile 对象
                                System.out.println("选中的文件: " + selectedFile1.getPath()+"wenjianlujing"+diff.targetFilePath);

                                // 执行文件恢复操作
                                recover.writeFile(newContentLine2, diff.targetFilePath);
                            });
                        }
                    }
                }
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

    //传出来两个文件的包含每一行内容的List
    public List<String> showFileContent(String diffFilePath,  JTextArea textArea,int textAreaNum) {
        // 解析 .diff 文件
        showdiff.Diff diff = showdiff.parseDiffFile(diffFilePath);
        // 读取初始文件内容
        String originalContent = showdiff.readFileContent(diff.originalFilePath);
        String targetContent = showdiff.readFileContent(diff.targetFilePath);
        if (originalContent == null) {
            return null;
        }
        if (textAreaNum == 1) {
            // 应用 .diff 差异，生成新文件内容
            String newContent = showdiff.applyDiff(originalContent, diff.diffLines);
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

    //生成新的.diff文件
    public void generateNewDiffForDisplay(List<String> newContent, List<String> TargetContent) {
        // 获得当前版本和base版本的diff内容
        System.out.printf("###进入generateNewDiffForDisplay方法\n");
        Patch<String> patch = DiffUtils.diff(newContent, TargetContent);
        System.out.printf("###对比两个不同的list结束\n");
        System.out.printf("###进入if条件判断\n");
        // 如果有变化，生成 unified diff 文件
        String diffFileName = "comparement.diff";

        // 创建相应的文件夹，并将diff文件写入文件夹当中
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                System.out.printf("###进入try\n");
                if (!snapshotDirectory.exists()) {
                    System.out.println("######### snapshotDirectory 不存在: " + snapshotDirectory);
                } else {
                    //String diffContent = generateDiff(originalFilePath, currentFilePath, originalLines, patch);
                    System.out.printf("###进入生成的步骤\n");

                    // 使用 DiffUtils 来生成统一 diff
                    List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                            "originalFileName",  // 原始文件名
                            "currentFileName",   // 当前文件名
                            newContent,     // 原始文件内容
                            patch,             // Patch 对象
                            1                  // 上下文行数，一般为 3
                    );

                    // 将生成的 diff 列表合并为单个字符串
                    String diffContentForDisplay = String.join("\n", unifiedDiff);
                    System.out.printf("###生成了diff文件\n");
                    FileUtil.writeToFile(new java.io.File("C:/Users/lrbde/IdeaProjects/test1/src/difference.diff"), diffContentForDisplay);

                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });


    }
    private void highlightDifferences(List<String> file1Lines, List<String> file2Lines, List<String> diffLines, JTextArea textArea1, JTextArea textArea2) throws BadLocationException {
        Highlighter highlighter1 = textArea1.getHighlighter();
        Highlighter highlighter2 = textArea2.getHighlighter();

        int pos1 = 0;  // 记录第一个文件在 JTextArea 中的偏移位置
        int pos2 = 0;  // 记录第二个文件在 JTextArea 中的偏移位置
        int lineIndex1=0;
        int lineIndex2=0;
        for (String diffLine : diffLines) {
            // 解析 diff 文件中的差异信息
            if (diffLine.startsWith("@@")) {
                String[] parts = diffLine.split(" ");
                String originalInfo1 = parts[1]; // 获取原始文件的行信息
                String[] originalRange1 = originalInfo1.substring(1).split(",");
                lineIndex1 = Integer.parseInt(originalRange1[0]); // 获取修改的起始行号（数组从0开始）
                String originalInfo2 = parts[2]; // 获取原始文件的行信息
                String[] originalRange2 = originalInfo2.substring(1).split(",");
                lineIndex2 = Integer.parseInt(originalRange2[0]); // 获取修改的起始行号（数组从0开始）
                // diff 行，解析出修改范围
                continue;
            }
            else if (diffLine.startsWith("-")) {
                // 删除的行，左边高亮红色
                int startPos = calculateStartPos(file1Lines,lineIndex1);
                int endPos = startPos + diffLine.length();
                highlighter1.addHighlight(startPos, endPos, new DefaultHighlighter.DefaultHighlightPainter(Color.RED));
                pos1 += 1;
                lineIndex1++;
                continue;
            } else if (diffLine.startsWith("+")) {

                // 新增的行，右边高亮绿色
                int startPos2 = calculateStartPos(file2Lines,lineIndex2);
                int endPos2 = startPos2 + diffLine.length();
                System.out.println("Highlighting from " + startPos2 + " to " + endPos2 + " for content: " + diffLine.length());
                highlighter2.addHighlight(startPos2, endPos2, new DefaultHighlighter.DefaultHighlightPainter(Color.GREEN));
                pos2 +=1;
                lineIndex2++;
                continue;
            }
        }

    }
    public int calculateStartPos(List<String> lines, int lineIndex) {
        int startPos = 0;

        // 遍历 lineIndex 之前的所有行，累加每一行的长度和换行符
        for (int i = 0; i < lineIndex; i++) {
            startPos += lines.get(i).length();  // 加上该行的字符数
            startPos += 1;  // 换行符的长度，一般为 1（\n）
        }
        return startPos;  // 返回第 lineIndex 行的起始位置
    }
}
