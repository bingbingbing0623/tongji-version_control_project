package com.qiqv.demo3;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;


// 这个类是项目启动的时候触发，创建MainManager实例以及开启自动保存
public class PluginStart implements ProjectManagerListener {
    private static MainManager mainManager;

    @Override
    // 这里显示移除了但是还能接着用（
    public void projectOpened (@NotNull Project project) {
        if(project != null) {
            mainManager = new MainManager(project);
            mainManager.startAutoSave();
        }
    }

    public void projectClosed(@NotNull Project project) {
        if(project != null) {
            mainManager.stopAutoSave();
        }
    }

    public static MainManager getInstance() {
        if(mainManager != null) {
            return mainManager;
        }
            return null;
    }

}
