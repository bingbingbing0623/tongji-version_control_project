package com.qiqv.demo3;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

public class MainManager {
    private final VersionManager versionManager;
    private final mainwindow mainwindow;
    private final MyFileListener myFileListener;

    public MainManager(Project project) {
        this.versionManager = new VersionManager(project);
        this.mainwindow = new mainwindow();
        this.myFileListener = new MyFileListener(this.versionManager);//将versionManager引入，并实例化监听器
        this.myFileListener.registerListener(this.myFileListener);//注册监听器
    }

    public void startAutoSave() {
        if (versionManager != null) {
            versionManager.startAutoSave(10); // 开始自动保存
        }
    }

    public void stopAutoSave(){
        if (versionManager != null) {
            versionManager.stopAutoSave(); // 停止自动保存
        }
    }

    public void showUI(VirtualFile directory){
        if (versionManager != null) {
            mainwindow.showSavedContentUI(directory,versionManager.getProject());
        }
    }


}
