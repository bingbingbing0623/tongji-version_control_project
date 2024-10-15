package com.qiqv.demo3;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

public class MyFileListener implements VirtualFileListener {

    private VersionManager versionManager;

    // 通过构造函数传递 A 类的实例
    public MyFileListener(VersionManager versionManager) {
        this.versionManager = versionManager;
    }

    // 实现文件删除监听逻辑
    @Override
    public void fileDeleted(@NotNull VirtualFileEvent event) {
        System.out.println("#####文件或目录删除: " + event.getFile().getPath());
        versionManager.setHasDeleteFile(true);
    }

    // 注册监听器
    public static void registerListener(MyFileListener myFileListener) {
        VirtualFileManager.getInstance().addVirtualFileListener(myFileListener);
    }

    // 取消监听器（如果需要的话）
    public static void unregisterListener(MyFileListener myFileListener) {
        VirtualFileManager.getInstance().removeVirtualFileListener(myFileListener);
    }
}
