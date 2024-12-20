package com.qiqv.demo3;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;

public class VersionManager {
    private final Project project;
    private final List<VirtualFile> savedFiles = new ArrayList<>(); // 用于保存项目中所有文件
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD); // 定时任务执行器
    private boolean saveWholeFiles = true; // 标记是否保存整个项目文件
    private boolean isBaseSave = false; // 标记初始版本是否保存
    private boolean tryNewSave = false; // 标记是否尝试重新保存base版本
    private boolean hasDeleteFile = false; // 标记是否删除文件
    private VirtualFile snapshotDirectory; // 记录snapshot文件夹位置
    private VirtualFile rootFileDirectory; // 记录根文件位置
    private String baseVersionDirectory; // 记录base版本位置
    private VirtualFile baseVersionPath; // 记录base版本路径
    private VirtualFile timeStampedFolder; // 本轮的时间戳文件夹位置

    public VersionManager(Project project) {
        this.project = project;
    }

    public Project getProject() {
        return project;
    }

    public void setHasDeleteFile(boolean tag) {
        this.hasDeleteFile = tag;
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
        startAutoSave(5); // 继续下一个定时任务，间隔5秒
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
                VirtualFile versionFolder = snapshotFolder.createChildDirectory(this, "version");
                VirtualFile diffFolder = snapshotFolder.createChildDirectory(this, "diff");
            }
            snapshotDirectory = snapshotFolder;

            // 以当前时间命名创建一个新文件夹
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String folderName = "Version_" + timeStamp;  // 添加 "Version" 前缀
            VirtualFile versionFolder = snapshotDirectory.findChild("version");// 10.15
            VirtualFile timeStampedFolder = versionFolder.createChildDirectory(this, folderName);//10.15
            //VirtualFile timeStampedFolder = snapshotFolder.createChildDirectory(this, folderName);

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
        boolean echoTag = true; // 用于标记每一次新的遍历是否开始
        try {
            for (VirtualFile file : rootDirectory.getChildren()) {
                // 对于系统文件或者snapshot文件直接continue
                if(file.getName().equals("snapshot") || file.getName().equals(".gitignore") || file.getName().equals(".idea") || file.getName().equals("compare")) {
                    continue;
                }
                // 用子序列来确定相对位置，方便后面确定originalFilePath
                String relativePath = file.getPath().substring(rootFileDirectory.getPath().length() + 1);
                String originalFilePath = baseVersionDirectory + "/" + relativePath; // 原始文件路径
                String currentFilePath = file.getPath(); // 当前文件路径
                // 判断是否存在删除文件的情况，以及判断当前文件是否为新建的
                // 当文件存在删除的时候直接执行else语句，当前文件有新建的时候执行else语句，其余情况执行if语句
                if (  (hasDeleteFile==false) && (!isBaseSave || findFileInDirectory(baseVersionPath, file.getName())==true)   ) {
                    // 将整个项目进行保存作为baseVersion版本
                    if (!isBaseSave) {
                        VirtualFile versionFolder = snapshotDirectory.findChild("version");
                        // 获取snapshot文件夹内的所有文件
                        VirtualFile[] snapshotFiles = versionFolder.getChildren();
                        // 现在获取baseVersion的directory, baseVersion就是文件夹最后一个文件
                        VirtualFile lastSnapshotFile = snapshotFiles.length > 0 ? snapshotFiles[snapshotFiles.length - 1] : null;
                        baseVersionDirectory = lastSnapshotFile.getPath();
                        baseVersionPath = lastSnapshotFile;
                        isBaseSave = true;
                    }
                    // 对file进行判断，判断file是目录还是文件。如果是目录则遍历，不是则进行版本对比
                    if (file.isDirectory() ) {
                        generateDiffFiles(file); // 递归遍历子目录
                        if (tryNewSave) {
                            tryNewSave = false;
                            return;
                        }
                    } else if (!file.isDirectory() ) {
                        if (Files.exists(Paths.get(originalFilePath))) {
                            // 获得相同文件之前版本和当前版本的内容
                            List<String> originalLines = Files.readAllLines(Paths.get(originalFilePath));
                            List<String> currentLines = Files.readAllLines(Paths.get(currentFilePath));
                            // 获得当前版本和base版本的diff内容
                            Patch<String> patch = DiffUtils.diff(originalLines, currentLines);
                            if (!patch.getDeltas().isEmpty()) {
                                // 如果有变化，生成 unified diff 文件
                                String diffFileName = file.getName() + ".diff";
                                String pureTimeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                                String timeStamp = "diff_" + pureTimeStamp;
                                // 由于labmad中不能修改变量，此处使用AtomicBoolean来替代echoTag
                                AtomicBoolean finalTag = new AtomicBoolean(echoTag); // 替代finalTag
                                // 生成差异文件
                                WriteCommandAction.runWriteCommandAction(project, () -> {
                                    try {
                                        if (!snapshotDirectory.exists()) {
                                            System.out.println("snapshotDirectory 不存在: " + snapshotDirectory);
                                        } else {
                                            if (finalTag.get()) {  // 使用finalTag.get()检查其值
                                                VirtualFile diffFolder = snapshotDirectory.findChild("diff");
                                                timeStampedFolder = diffFolder.createChildDirectory(this, timeStamp);
                                                finalTag.set(false);  // 修改finalTag的值
                                            }
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
                                }
                            }
                        }
                    }
                }
                // 检测到当前项目有新增文件或者文件夹，终止当前生成diff文件，生成新的baseversion
                else {
                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        //删除当前的timeStampedFolder，终止当前，重置参数
                        try {
                            if (timeStampedFolder != null) {
                                timeStampedFolder.delete(this); // 删除 timeStampedFolder 文件夹
                                System.out.println("删除文件夹: " + timeStampedFolder.getPath());
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            System.out.println("无法删除文件夹: " + timeStampedFolder.getPath());
                        }
                    });
                    // 重置相关的参数
                    isBaseSave = false;
                    baseVersionDirectory = null;
                    echoTag = false;
                    tryNewSave = true;
                    saveWholeFiles = true;
                    hasDeleteFile = false;
                    return;
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
                1                  // 上下文行数，
        );

        // 将生成的 diff 列表合并为单个字符串
        return String.join("\n", unifiedDiff);
    }
}