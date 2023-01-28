package com.xayah.databackup.activity.processing

import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewModelScope
import com.xayah.databackup.App
import com.xayah.databackup.adapter.ProcessingItemAdapter
import com.xayah.databackup.adapter.ProcessingTaskAdapter
import com.xayah.databackup.data.*
import com.xayah.databackup.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProcessingBackupAppActivity : ProcessingBaseActivity() {
    lateinit var viewModel: ProcessingBaseViewModel

    /**
     * 全局单例对象
     */
    private val globalObject = GlobalObject.getInstance()

    // 备份信息列表
    private val backupInfoList by lazy {
        MutableStateFlow(mutableListOf<BackupInfo>())
    }

    // 应用备份列表
    private val appInfoBackupList
        get() = GlobalObject.getInstance().appInfoBackupMap.value.values.toList()
            .filter { (it.detailBackup.selectApp || it.detailBackup.selectData) && it.isOnThisDevice }
            .toMutableList()
    private val appInfoBackupListNum
        get() = run {
            val appInfoBaseNum = AppInfoBaseNum(0, 0)
            for (i in appInfoBackupList) {
                if (i.detailBackup.selectApp) appInfoBaseNum.appNum++
                if (i.detailBackup.selectData) appInfoBaseNum.dataNum++
            }
            appInfoBaseNum
        }

    // 任务列表
    private val processingTaskList by lazy {
        MutableStateFlow(mutableListOf<ProcessingTask>())
    }

    // Processing项目哈希表
    private val processingItemMap by lazy {
        MutableStateFlow(hashMapOf<String, ProcessingItem>())
    }

    override fun initialize(viewModel: ProcessingBaseViewModel) {
        this.viewModel = viewModel
        viewModel.viewModelScope.launch {
            // 加载配置
            backupInfoList.emit(Command.getBackupInfoList())
            if (globalObject.appInfoBackupMap.value.isEmpty()) {
                globalObject.appInfoBackupMap.emit(Command.getAppInfoBackupMap())
            }
            if (globalObject.appInfoRestoreMap.value.isEmpty()) {
                globalObject.appInfoRestoreMap.emit(Command.getAppInfoRestoreMap())
            }

            // 设置适配器
            viewModel.mAdapter.apply {
                for (i in appInfoBackupList) processingTaskList.value.add(
                    ProcessingTask(
                        appName = i.detailBase.appName,
                        packageName = i.detailBase.packageName,
                        app = i.detailBackup.selectApp,
                        data = i.detailBackup.selectData,
                        appIcon = i.detailBase.appIcon
                    )
                )
                register(ProcessingTaskAdapter())
                items = processingTaskList.value
                notifyDataSetChanged()
            }

            viewModel.mAdapterItems.apply {
                register(ProcessingItemAdapter())
            }

            // 设置备份状态
            viewModel.btnText.set(GlobalString.backup)
            viewModel.btnDesc.set(GlobalString.clickTheRightBtnToStart)
            viewModel.progressMax.set(appInfoBackupList.size)
            viewModel.progressText.set("${GlobalString.progress}: ${viewModel.progress.get()}/${viewModel.progressMax.get()}")
            viewModel.totalTip.set(GlobalString.ready)
            appInfoBackupListNum.apply {
                viewModel.totalProgress.set("${GlobalString.selected} ${this.appNum} ${GlobalString.application}, ${this.dataNum} ${GlobalString.data}, ${App.globalContext.readBackupUser()} ${GlobalString.backupUser}")
            }
            viewModel.isReady.set(true)
            viewModel.isFinished.postValue(false)
        }
    }

    override fun onFabClick() {
        if (!viewModel.isFinished.value!!) {
            if (viewModel.isProcessing.get().not()) {
                viewModel.isProcessing.set(true)
                viewModel.totalTip.set(GlobalString.backupProcessing)
                viewModel.viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        // 记录开始时间戳
                        val startTime = App.getTimeStamp()
                        // 记录开始备份目录大小
                        val startSize = Command.countSize(App.globalContext.readBackupSavePath())

                        // 获取默认输入法和无障碍
                        val keyboard = Bashrc.getKeyboard()
                        val services = Bashrc.getAccessibilityServices()

                        // 备份自身
                        if (App.globalContext.readIsBackupItself())
                            Command.backupItself(
                                "com.xayah.databackup",
                                App.globalContext.readBackupSavePath(),
                                App.globalContext.readBackupUser()
                            )

                        for ((index, i) in appInfoBackupList.withIndex()) {
                            val date =
                                if (App.globalContext.readBackupStrategy() == BackupStrategy.Cover) GlobalString.cover else App.getTimeStamp()
                            // 准备备份卡片数据
                            viewModel.appName.set(i.detailBase.appName)
                            viewModel.packageName.set(i.detailBase.packageName)
                            viewModel.appVersion.set(i.detailBackup.versionName)
                            viewModel.appIcon.set(i.detailBase.appIcon)

                            val packageName = viewModel.packageName.get()!!
                            val userId = App.globalContext.readBackupUser()
                            val compressionType = App.globalContext.readCompressionType()
                            val outPutPath = "${Path.getBackupDataSavePath()}/${packageName}/$date"
                            val outPutIconPath =
                                "${Path.getBackupDataSavePath()}/${packageName}/icon.png"
                            val userPath = "${Path.getUserPath()}/${packageName}"
                            val userDePath = "${Path.getUserDePath()}/${packageName}"
                            val dataPath = "${Path.getDataPath()}/${packageName}"
                            val obbPath = "${Path.getObbPath()}/${packageName}"

                            // 设置适配器
                            viewModel.mAdapterItems.apply {
                                val size = processingItemMap.value.size
                                processingItemMap.value.clear()
                                clearProcessingItems(viewModel, size)
                                if (i.detailBackup.selectApp) {
                                    // 检查是否备份APK
                                    processingItemMap.value[ProcessingItemTypeAPK] =
                                        ProcessingItem.APK()
                                }
                                if (i.detailBackup.selectData) {
                                    // 检查是否备份数据
                                    Command.ls(userPath).apply {
                                        if (this) processingItemMap.value[ProcessingItemTypeUSER] =
                                            ProcessingItem.USER()
                                    }
                                    Command.ls(userDePath).apply {
                                        if (this) processingItemMap.value[ProcessingItemTypeUSERDE] =
                                            ProcessingItem.USERDE()
                                    }
                                    Command.ls(dataPath).apply {
                                        if (this) processingItemMap.value[ProcessingItemTypeDATA] =
                                            ProcessingItem.DATA()
                                    }
                                    Command.ls(obbPath).apply {
                                        if (this) processingItemMap.value[ProcessingItemTypeOBB] =
                                            ProcessingItem.OBB()
                                    }
                                }
                                items =
                                    processingItemMap.value.values.sortedBy { it.weight }
                                refreshProcessingItems(viewModel)
                            }

                            // 开始备份
                            var state = true // 该任务是否成功完成
                            if (processingItemMap.value.containsKey(ProcessingItemTypeAPK)) {
                                processingItemMap.value[ProcessingItemTypeAPK]?.isProcessing = true
                                refreshProcessingItems(viewModel)

                                // 备份应用
                                Command.compressAPK(
                                    compressionType,
                                    packageName,
                                    outPutPath,
                                    userId,
                                    i.detailBackup.appSize
                                ) {
                                    setProcessingItem(
                                        it,
                                        processingItemMap.value[ProcessingItemTypeAPK]
                                    )
                                    viewModel.viewModelScope.launch {
                                        refreshProcessingItems(viewModel)
                                    }
                                }.apply {
                                    if (!this) state = false
                                    // 保存apk大小
                                    else i.detailBackup.appSize = Command.countSize(
                                        Bashrc.getAPKPath(i.detailBase.packageName, userId).second,
                                        1
                                    )
                                }

                                processingItemMap.value[ProcessingItemTypeAPK]?.isProcessing = false
                                refreshProcessingItems(viewModel)
                            }
                            if (processingItemMap.value.containsKey(ProcessingItemTypeUSER)) {
                                processingItemMap.value[ProcessingItemTypeUSER]?.isProcessing = true
                                refreshProcessingItems(viewModel)

                                // 备份User
                                Command.compress(
                                    compressionType,
                                    "user",
                                    packageName,
                                    outPutPath,
                                    Path.getUserPath(),
                                    i.detailBackup.userSize
                                ) {
                                    setProcessingItem(
                                        it,
                                        processingItemMap.value[ProcessingItemTypeUSER]
                                    )
                                    viewModel.viewModelScope.launch {
                                        refreshProcessingItems(viewModel)
                                    }
                                }.apply {
                                    if (!this) state = false
                                    // 保存user大小
                                    else i.detailBackup.userSize = Command.countSize(userPath, 1)
                                }

                                processingItemMap.value[ProcessingItemTypeUSER]?.isProcessing =
                                    false
                                refreshProcessingItems(viewModel)
                            }
                            if (processingItemMap.value.containsKey(ProcessingItemTypeUSERDE)) {
                                processingItemMap.value[ProcessingItemTypeUSERDE]?.isProcessing =
                                    true
                                refreshProcessingItems(viewModel)

                                // 备份User_de
                                Command.compress(
                                    compressionType,
                                    "user_de",
                                    packageName,
                                    outPutPath,
                                    Path.getUserDePath(),
                                    i.detailBackup.userDeSize
                                ) {
                                    setProcessingItem(
                                        it,
                                        processingItemMap.value[ProcessingItemTypeUSERDE]
                                    )
                                    viewModel.viewModelScope.launch {
                                        refreshProcessingItems(viewModel)
                                    }
                                }.apply {
                                    if (!this) state = false
                                    // 保存user_de大小
                                    else i.detailBackup.userDeSize =
                                        Command.countSize(userDePath, 1)
                                }

                                processingItemMap.value[ProcessingItemTypeUSERDE]?.isProcessing =
                                    false
                                refreshProcessingItems(viewModel)
                            }
                            if (processingItemMap.value.containsKey(ProcessingItemTypeDATA)) {
                                processingItemMap.value[ProcessingItemTypeDATA]?.isProcessing = true
                                refreshProcessingItems(viewModel)

                                // 备份Data
                                Command.compress(
                                    compressionType,
                                    "data",
                                    packageName,
                                    outPutPath,
                                    Path.getDataPath(),
                                    i.detailBackup.dataSize
                                ) {
                                    setProcessingItem(
                                        it,
                                        processingItemMap.value[ProcessingItemTypeDATA]
                                    )
                                    viewModel.viewModelScope.launch {
                                        refreshProcessingItems(viewModel)
                                    }
                                }.apply {
                                    if (!this) state = false
                                    // 保存data大小
                                    else i.detailBackup.dataSize = Command.countSize(dataPath, 1)
                                }

                                processingItemMap.value[ProcessingItemTypeDATA]?.isProcessing =
                                    false
                                refreshProcessingItems(viewModel)
                            }
                            if (processingItemMap.value.containsKey(ProcessingItemTypeOBB)) {
                                processingItemMap.value[ProcessingItemTypeOBB]?.isProcessing = true
                                refreshProcessingItems(viewModel)

                                // 备份Obb
                                Command.compress(
                                    compressionType,
                                    "obb",
                                    packageName,
                                    outPutPath,
                                    Path.getObbPath(),
                                    i.detailBackup.obbSize
                                ) {
                                    setProcessingItem(
                                        it,
                                        processingItemMap.value[ProcessingItemTypeOBB]
                                    )
                                    viewModel.viewModelScope.launch {
                                        refreshProcessingItems(viewModel)
                                    }
                                }.apply {
                                    if (!this) state = false
                                    // 保存obb大小
                                    else i.detailBackup.obbSize = Command.countSize(obbPath, 1)
                                }

                                processingItemMap.value[ProcessingItemTypeOBB]?.isProcessing = false
                                refreshProcessingItems(viewModel)
                            }
                            i.detailBackup.date = date

                            // 保存应用图标
                            if (App.globalContext.readIsBackupIcon()) {
                                com.topjohnwu.superuser.io.SuFile(outPutIconPath).apply {
                                    val outputStream = outputStream()
                                    i.detailBase.appIcon?.toBitmap()
                                        ?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                                    outputStream.flush()
                                    outputStream.close()
                                }
                            }

                            if (state) {
                                val detail = AppInfoDetailRestore().apply {
                                    this.selectApp = false
                                    this.selectData = false
                                    this.hasApp = true
                                    this.hasData = true
                                    this.versionName = i.detailBackup.versionName
                                    this.versionCode = i.detailBackup.versionCode
                                    this.appSize = i.detailBackup.appSize
                                    this.userSize = i.detailBackup.userSize
                                    this.userDeSize = i.detailBackup.userDeSize
                                    this.dataSize = i.detailBackup.dataSize
                                    this.obbSize = i.detailBackup.obbSize
                                    this.date = i.detailBackup.date
                                }
                                if (GlobalObject.getInstance().appInfoRestoreMap.value.containsKey(
                                        packageName
                                    ).not()
                                ) {
                                    GlobalObject.getInstance().appInfoRestoreMap.value[packageName] =
                                        AppInfoRestore().apply {
                                            this.detailBase = i.detailBase
                                            this.firstInstallTime = i.firstInstallTime
                                        }
                                }
                                val appInfoRestore =
                                    GlobalObject.getInstance().appInfoRestoreMap.value[packageName]!!

                                val itemIndex =
                                    appInfoRestore.detailRestoreList.indexOfFirst { date == it.date }
                                if (itemIndex == -1) {
                                    // RestoreList中不存在该Item
                                    appInfoRestore.detailRestoreList.add(detail)
                                    appInfoRestore.restoreIndex++
                                } else {
                                    // RestoreList中已存在该Item
                                    appInfoRestore.detailRestoreList[itemIndex] = detail
                                }
                                viewModel.successList.value.add(processingTaskList.value[index])
                            } else {
                                viewModel.failedList.value.add(processingTaskList.value[index])
                            }
                            viewModel.progress.set(index + 1)
                            viewModel.progressText.set("${GlobalString.progress}: ${viewModel.progress.get()}/${viewModel.progressMax.get()}")
                        }
                        val endTime = App.getTimeStamp()
                        val endSize = Command.countSize(App.globalContext.readBackupSavePath())
                        backupInfoList.value.add(
                            BackupInfo(
                                Command.getVersion(),
                                startTime,
                                endTime,
                                startSize,
                                endSize,
                                "app",
                                App.globalContext.readBackupUser()
                            )
                        )
                        viewModel.totalTip.set(GlobalString.backupFinished)
                        viewModel.totalProgress.set("${viewModel.successNum + viewModel.failedNum} ${GlobalString.total}")
                        viewModel.isProcessing.set(false)
                        viewModel.isFinished.postValue(true)
                        viewModel.btnText.set(GlobalString.finish)
                        viewModel.btnDesc.set(GlobalString.clickTheRightBtnToFinish)

                        // 恢复默认输入法和无障碍
                        keyboard.apply {
                            if (this.first) Bashrc.setKeyboard(this.second)
                        }
                        services.apply {
                            if (this.first) Bashrc.setAccessibilityServices(this.second)
                        }

                        // 保存列表数据
                        GsonUtil.saveAppInfoBackupMapToFile(GlobalObject.getInstance().appInfoBackupMap.value)
                        GsonUtil.saveAppInfoRestoreMapToFile(GlobalObject.getInstance().appInfoRestoreMap.value)
                    }
                }
            }
        } else {
            finish()
        }
    }
}