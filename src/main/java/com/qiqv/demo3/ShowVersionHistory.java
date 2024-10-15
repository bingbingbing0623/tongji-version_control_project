package com.qiqv.demo3;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
//
// 这个类是显示UI界面
//
public class ShowVersionHistory extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();

        if (project != null) {
            VirtualFile projectRoot = project.getBaseDir();
            MainManager mainManager = PluginStart.getInstance();
            if (mainManager != null) {
                mainManager.showUI(projectRoot); // 调用showUI方法
            }
        }
    }
}
