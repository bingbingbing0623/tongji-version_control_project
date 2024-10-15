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
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;


// 这个类基本没改，添加了自动保存的两个方法，以及在saveEntireProject中去掉了ui显示（把ui显示单独放在ShowVersionHistory类了）
public class VersionManager {
    private final Project project;
    private final List<VirtualFile> savedFiles = new ArrayList<>(); // 用于保存项目中所有文件
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD); // 定时任务执行器
    private boolean saveWholeFiles = true; // 标记是否保存整个项目文件
    private boolean isBaseSave = false; // 标记初始版本是否保存
    private boolean tryNewSave = false;
    private VirtualFile snapshotDirectory; // 记录snapshot文件夹位置
    private VirtualFile rootFileDirectory; // 记录根文件位置
    private String baseVersionDirectory; // 记录base版本位置
    private VirtualFile baseVersionPath; // 记录base版本路径
    private VirtualFile timeStampedFolder; // 本轮的时间戳文件夹位置

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
            if (saveWholeFiles) {
                saveAllFilesInDirectory(projectRoot); // 递归保存所有文件
                saveWholeFiles = false; // 更新标记为
                ApplicationManager.getApplication().invokeLater(() -> {
                    ApplicationManager.getApplication().runWriteAction(() -> createSnapshotFolder(projectRoot)); // 在 EDT 中执行写操作
                });
            } else {
                // 后续保存时对比并生成差异文件
                System.out.println("\n");
                generateDiffFiles(projectRoot);
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    FileDocumentManager.getInstance().saveAllDocuments();  // 保存所有未保存的文件
                    System.out.println("保存成功"); // 打印日志，表示保存成功
                } catch (Exception e) {
                    System.err.println("保存失败：" + e.getMessage()); // 捕获异常，表示保存失败
                }
            });

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

    public boolean findFileInDirectory(VirtualFile directory, String targetFileName) {
        // 遍历当前目录下的所有文件和子目录
        for (VirtualFile file : directory.getChildren()) {
            if (file.getName().equals(targetFileName)) {
                return true;
            }
            if (file.isDirectory()) {
                // 如果是子目录，递归调用该函数
                boolean result = findFileInDirectory(file, targetFileName);
                if (result) {
                    return true; // 如果在子目录中找到文件，返回 1
                }
            } else if (file.getName().equals(targetFileName)) {
                // 如果文件名匹配，返回 1
                return false;
            }
        }
        // 如果遍历所有文件和子目录都未找到，返回 0
        return false;
    }

    // 生成文件差异并保存为 unified diff 格式
    private void generateDiffFiles(VirtualFile rootDirectory) {
        boolean echoTag = true; // 用于记录每一轮轮内的时间戳文件
        try {
            // 遍历项目根目录下的所有文件和子目录
            for (VirtualFile file : rootDirectory.getChildren()) {
                if(file.getName().equals("snapshot") || file.getName().equals(".gitignore") || file.getName().equals(".idea")){
                    continue;
                }
                String relativePath = file.getPath().substring(rootFileDirectory.getPath().length() + 1);

                // 用子序列来确定相对位置，方便后面确定originalFilePath
                String originalFilePath = baseVersionDirectory + "/" + relativePath; // 原始文件路径
                String currentFilePath = file.getPath(); // 当前文件路径
                System.out.println("######当前文件内容: " + currentFilePath);
                // 判断文件是否为新建的
                System.out.println("######判断是否存在"+file.getName());
                if (!isBaseSave || findFileInDirectory(baseVersionPath, file.getName())) {
                    // 将baseVersion保存
                    if (!isBaseSave) {
                        // 获取snapshot文件夹内的所有文件
                        VirtualFile[] snapshotFiles = snapshotDirectory.getChildren();
                        // 现在获取baseVersion的directory, baseVersion就是snapshot文件夹最后一个文件
                        VirtualFile lastSnapshotFile = snapshotFiles.length > 0 ? snapshotFiles[snapshotFiles.length - 1] : null;
                        baseVersionDirectory = lastSnapshotFile.getPath();
                        baseVersionPath = lastSnapshotFile;
                        isBaseSave = true;
                    }
                    // 对file进行判断，判断file是目录还是文件，并且不考虑.gitignore和.idea的变化
                    if (file.isDirectory() ) {
                        generateDiffFiles(file); // 递归遍历子目录
                        if (tryNewSave) {
                            return;
                        }
                    } else if (!file.isDirectory() ) {
                        if (Files.exists(Paths.get(originalFilePath))) {
                            List<String> originalLines = Files.readAllLines(Paths.get(originalFilePath));
                            List<String> currentLines = Files.readAllLines(Paths.get(currentFilePath));
                            // 获得当前版本和base版本的diff内容
                            Patch<String> patch = DiffUtils.diff(originalLines, currentLines);
                            System.out.println("######originalFilePath: " + originalFilePath);
                            System.out.println("######currentFilePath: " + currentFilePath);
                            System.out.println("######现在的文件内容: " + currentLines);
                            System.out.println("######patch: " + patch);
                            if (!patch.getDeltas().isEmpty()) {
                                System.out.println("######有改动了");
                                // 如果有变化，生成 unified diff 文件
                                String diffFileName = file.getName() + ".diff";
                                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                                // 使用AtomicBoolean来替代echoTag
                                AtomicBoolean finalTag = new AtomicBoolean(echoTag); // 替代finalTag
                                WriteCommandAction.runWriteCommandAction(project, () -> {
                                    try {
                                        if (!snapshotDirectory.exists()) {
                                            System.out.println("snapshotDirectory 不存在: " + snapshotDirectory);
                                        } else {
                                            System.out.println("进来准备写了");
                                            if (finalTag.get()) {  // 使用finalTag.get()检查其值
                                                timeStampedFolder = snapshotDirectory.createChildDirectory(this, timeStamp);
                                                finalTag.set(false);  // 修改finalTag的值
                                            }
                                            System.out.println("timeStampedFolder"+timeStampedFolder);
                                            VirtualFile diffFile = timeStampedFolder.createChildData(this, diffFileName);
                                            String diffContent = generateDiff(originalFilePath, currentFilePath, originalLines, patch);
                                            FileUtil.writeToFile(new java.io.File(diffFile.getPath()), diffContent);
                                            System.out.println("生成差异文件: " + diffFile.getPath());
                                        }
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                });
                                // 由lumbda当中的finaltag确定是否修改echotag的值
                                if(!finalTag.get()) {
                                    echoTag = false; // 第一个diff生成后，时间戳文件夹固定
                                    System.out.println("换了一次echotag现在是"+echoTag);
                                }
                            }
                        }
                    }
                }
                // 检测到当前项目有新增文件或者文件夹，终止当前生成diff文件，生成新的baseversion
                else {
                    System.out.println("#######检测到新增文件");
                    System.out.println("#######baseaVersionDirectory: " + baseVersionDirectory);
                    System.out.println("#######文件名"+file.getName());
                    //删除当前的timeStampedFolder，终止当前，重置参数
                    try {
                        if (timeStampedFolder != null) {
                            timeStampedFolder.delete(this); // 删除 timeStampedFolder 文件夹
                            System.out.println("删除文件夹: " + timeStampedFolder.getPath());
                        }
                        // 重置相关的参数
                        isBaseSave = false;
                        baseVersionDirectory = null;
                        echoTag = false;
                        tryNewSave = true;
                        saveWholeFiles = true;
                        return;
                    } catch (IOException e) {
                        e.printStackTrace();
                        System.out.println("无法删除文件夹: " + timeStampedFolder.getPath());
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
                List<String> newContentLine = showFileContent(diffFilePath,textArea1,1);
                //当前内容
                List<String> targetContentLine = showFileContent(diffFilePath,textArea2,2);
                generateNewDiffForDisplay(newContentLine, targetContentLine);
                // 读取 diff 文件并解析差异
                // 找到 compare 文件夹下的 difference.diff 文件并解析
                VirtualFile projectRoot = project.getBaseDir();  // 获取项目的根目录
                File compareDir = new File(projectRoot.getPath() + "/compare");  // 动态创建 'compare' 文件夹路径
                // 生成 difference.diff 文件的完整路径
                File difference = new File(compareDir, "difference.diff");
                showdiff.Diff diff = showdiff.parseDiffFile(difference.getPath());
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

    // 生成新的 .diff 文件
    public void generateNewDiffForDisplay(List<String> newContent, List<String> targetContent) {
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
