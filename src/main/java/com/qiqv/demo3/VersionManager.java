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

import java.nio.charset.StandardCharsets; // 添加以使用UTF-8编码

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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



    public void showSavedContentUI(VirtualFile rootDirectory) {
        // 创建 JFrame 作为主窗口
        JFrame frame = new JFrame("Saved File Content");
        frame.setSize(1200, 600); // 设置窗口大小
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new GridLayout(1, 3)); // 将布局改为 1 行 3 列

        // 获取 snapshot 文件夹作为根目录
        VirtualFile snapshotFolder = rootDirectory.findChild("snapshot");
        if (snapshotFolder == null) {
            System.out.println("未找到 snapshot 文件夹");
            return;
        }

        // 创建 JTree 显示 snapshot 文件夹的内容
        JTree fileTree = createFileTree(snapshotFolder);
        frame.add(new JScrollPane(fileTree)); // 添加文件树到第一列

        // 创建 JTextArea 显示 .diff 文件内容（第二列）
        JTextArea textArea1 = new JTextArea();
        textArea1.setEditable(false); // 设置为只读
        frame.add(new JScrollPane(textArea1)); // 添加到第二列

        // 创建 JTextArea 显示被修改文件的当前内容（第三列）
        JTextArea textArea2 = new JTextArea();
        textArea2.setEditable(false); // 设置为只读
        frame.add(new JScrollPane(textArea2)); // 添加到第三列

        // 获取 IntelliJ 的配色方案
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        Font font = scheme.getFont(EditorFontType.PLAIN); // 获取默认字体

        // 设置字体和颜色
        textArea1.setFont(font);
        textArea1.setForeground(scheme.getDefaultForeground()); // 设置前景色
        textArea1.setBackground(scheme.getDefaultBackground()); // 设置背景色

        textArea2.setFont(font);
        textArea2.setForeground(scheme.getDefaultForeground());
        textArea2.setBackground(scheme.getDefaultBackground());

        // 添加树选择监听器
        fileTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (selectedNode != null && selectedNode.getUserObject() instanceof VirtualFile) {
                VirtualFile selectedFile = (VirtualFile) selectedNode.getUserObject();

                // 显示选中的 .diff 文件内容
                showDiffFileContent(selectedFile, textArea1); // 在中间显示
                // 显示被 .diff 文件修改的文件的当前内容
                String modifiedFilePath = getModifiedFilePathFromDiff(selectedFile);
                if (modifiedFilePath != null) {
                    showCurrentFileContent(modifiedFilePath, textArea2); // 显示右侧当前文件内容
                } else {
                    textArea2.setText("无法找到修改的文件");
                }
            }
        });

        // 显示窗口
        frame.setVisible(true);
    }

    // 显示 .diff 文件的内容到 textArea1
    private void showDiffFileContent(VirtualFile diffFile, JTextArea textArea) {
        try {
            // 读取并显示 .diff 文件内容（使用UTF-8编码）
            List<String> fileLines = Files.readAllLines(Paths.get(diffFile.getPath()), StandardCharsets.UTF_8);
            textArea.setText(String.join("\n", fileLines)); // 设置文本内容
        } catch (IOException e) {
            textArea.setText("无法加载 .diff 文件内容");
            e.printStackTrace();
        }
    }

    // 生成合并后的内容，不生成实际文件
    private String getMergedContent(VirtualFile selectedFile) {
        try {
            // 获取初版文件路径和内容
            String originalFilePath = getOriginalFilePath(selectedFile);
            List<String> originalLines = Files.readAllLines(Paths.get(originalFilePath), StandardCharsets.UTF_8); // 使用UTF-8编码读取文件

            // 获取当前文件内容
            List<String> currentLines = Files.readAllLines(Paths.get(selectedFile.getPath()), StandardCharsets.UTF_8); // 使用UTF-8编码读取文件

            // 生成差异patch
            Patch<String> patch = DiffUtils.diff(originalLines, currentLines);

            // 应用patch生成合并后的内容
            List<String> mergedLines = DiffUtils.patch(originalLines, patch);

            // 返回合并后的内容作为字符串
            return String.join("\n", mergedLines);

        } catch (Exception e) {
            e.printStackTrace();
            return "无法生成合并后的内容";
        }
    }

    // 获取初版文件的路径
    private String getOriginalFilePath(VirtualFile selectedFile) {
        // 假设初版文件保存在snapshot目录中，构造对应路径
        String relativePath = selectedFile.getPath().substring(rootFileDirectory.getPath().length() + 1);
        return snapshotDirectory.getPath() + "/" + relativePath;
    }

    // 创建项目文件的 JTree 仅显示 snapshot 文件夹的内容
    private JTree createFileTree(VirtualFile snapshotDirectory) {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(snapshotDirectory.getName());
        createTreeNodes(snapshotDirectory, rootNode); // 递归创建 snapshot 文件夹下的文件节点

        DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
        JTree fileTree = new JTree(treeModel);
        fileTree.setRootVisible(true); // 显示 snapshot 作为根节点
        return fileTree;
    }

    // 递归创建 snapshot 文件夹的 JTree 节点
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

    // 解析 .diff 文件，获取被修改文件的路径
    private String getModifiedFilePathFromDiff(VirtualFile diffFile) {
        try {
            List<String> diffLines = Files.readAllLines(Paths.get(diffFile.getPath()), StandardCharsets.UTF_8);

            // 使用正则表达式匹配被修改的文件路径 (例如 +++ b/path/to/file.java)
            Pattern pattern = Pattern.compile("^\\+\\+\\+\\s+(.*)$");
            for (String line : diffLines) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    // 提取被修改文件的路径
                    String relativePath = matcher.group(1);
                    System.out.println("#####relativePath"+relativePath);
                    // 构造完整的文件路径
                    return rootFileDirectory.getPath() + "/" + relativePath;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null; // 如果未找到文件路径，返回 null
    }

    // 显示被修改文件的当前内容到 textArea2
    private void showCurrentFileContent(String filePath, JTextArea textArea) {
        try {
            // 读取并显示当前文件内容（使用UTF-8编码）
            List<String> fileLines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
            textArea.setText(String.join("\n", fileLines)); // 设置文本内容
        } catch (IOException e) {
            textArea.setText("无法加载当前文件内容");
            e.printStackTrace();
        }
    }
}
