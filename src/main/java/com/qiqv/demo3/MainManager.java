package com.qiqv.demo3;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

// 这个类是负责实例化VersionManager，并且提供获得VersionManager的方法的接口
public class MainManager {
    private VersionManager versionManager;

    public MainManager(Project project) {
        this.versionManager = new VersionManager(project);
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
            versionManager.showSavedContentUI(directory);
        }
    }


}
