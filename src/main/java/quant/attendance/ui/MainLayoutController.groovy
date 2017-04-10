package quant.attendance.ui

import com.jfoenix.controls.JFXButton
import com.jfoenix.controls.JFXSnackbar
import com.jfoenix.controls.JFXTreeTableColumn
import com.jfoenix.controls.JFXTreeTableView
import com.jfoenix.controls.RecursiveTreeItem
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.event.ActionEvent
import javafx.event.Event
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.layout.StackPane
import javafx.stage.FileChooser
import org.fxmisc.richtext.StyleClassedTextArea
import quant.attendance.StageManager
import quant.attendance.bus.RxBus
import quant.attendance.database.DbHelper
import quant.attendance.event.OnDepartmentAddedEvent
import quant.attendance.event.OnEmployeeAddedEvent
import quant.attendance.excel.ExcelReader
import quant.attendance.excel.ExcelWriter
import quant.attendance.excel.InformantRegistry
import quant.attendance.model.DepartmentProperty
import quant.attendance.model.EmployeeProperty
import quant.attendance.scheduler.MainThreadSchedulers
import quant.attendance.util.Analyser
import quant.attendance.widget.drag.DragTextField
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Created by Administrator on 2017/4/8.
 */
class MainLayoutController implements Initializable{
    @FXML StackPane root
    @FXML JFXTreeTableView<DepartmentProperty> departmentTable
    @FXML JFXTreeTableColumn departmentName
    @FXML JFXTreeTableColumn startTime
    @FXML JFXTreeTableColumn endTime

    @FXML DragTextField attendanceFile

    @FXML JFXTreeTableView employeeTable
    @FXML JFXTreeTableColumn departmentEmployeeName
    @FXML JFXTreeTableColumn employeeName
    @FXML JFXTreeTableColumn employeeStartTime
    @FXML JFXTreeTableColumn employeeEndTime

    @FXML JFXButton createButton
    @FXML JFXButton exitButton

    @FXML JFXSnackbar snackBar
    @FXML JFXButton fileChoose1
    @FXML StyleClassedTextArea messageArea
    final def departmentItems=[], employeeItems=[]
    final def departmentProperties = FXCollections.observableArrayList()
    final def employeeProperties = FXCollections.observableArrayList()

    @Override
    void initialize(URL location, ResourceBundle resources) {
        snackBar.registerSnackbarContainer(root)
        final def stage=StageManager.instance.getStage(this)
        initTableItems()
        initEvent()
        attendanceFile.setDragListener{ processAttendanceFile(it)}
        exitButton.setOnMouseClicked({StageManager.instance.getStage(this)?.close()})
        setButtonMouseClicked(fileChoose1,stage,{processAttendanceFile(it)})
    }

    void initTableItems() {
        Observable.create({ sub ->
            def departmentItems = DbHelper.helper.queryDepartment()
            def employeeItems = DbHelper.helper.queryEmployeeRest()
            sub.onNext([departmentItems, employeeItems])
            sub.onCompleted()
        }).subscribeOn(Schedulers.io()).
                observeOn(MainThreadSchedulers.mainThread()).
                subscribe({
                    def departmentItems, employeeItems
                    (departmentItems, employeeItems) = it
                    this.departmentItems.addAll(departmentItems)
                    this.employeeItems.addAll(employeeItems)
                    initDepartmentPropertyTable(departmentItems)
                    initEmployeePropertyTable(employeeItems)
                }, { e -> e.printStackTrace() })
    }

    private void initDepartmentPropertyTable(items) {
        departmentName.setCellValueFactory({ departmentName.validateValue(it) ? it.value.value.departmentName : departmentName.getComputedValue(it) })
        startTime.setCellValueFactory({ startTime.validateValue(it) ? it.value.value.startDate : startTime.getComputedValue(it) })
        endTime.setCellValueFactory({ endTime.validateValue(it) ? it.value.value.endDate : endTime.getComputedValue(it) })
        !items?:items.each{ departmentProperties.add(it.toProperty()) }
        departmentTable.setRoot(new RecursiveTreeItem<DepartmentProperty>(departmentProperties, { it.getChildren() }))
        departmentTable.setShowRoot(false)
        departmentTable.selectionModel.select(0)
    }

    private void initEmployeePropertyTable(items) {
        departmentEmployeeName.setCellValueFactory({ departmentEmployeeName.validateValue(it) ? it.value.value.departmentName : departmentEmployeeName.getComputedValue(it) })
        employeeName.setCellValueFactory({ employeeName.validateValue(it) ? it.value.value.employeeName : employeeName.getComputedValue(it) })
        employeeStartTime.setCellValueFactory({ employeeStartTime.validateValue(it) ? it.value.value.startDate : employeeStartTime.getComputedValue(it) })
        employeeEndTime.setCellValueFactory({ employeeEndTime.validateValue(it) ? it.value.value.endDate : employeeEndTime.getComputedValue(it) })
        !items?:items.each{ employeeProperties.add(it.toProperty()) }
        employeeTable.setRoot(new RecursiveTreeItem<EmployeeProperty>(employeeProperties, { it.getChildren() }))
        employeeTable.setShowRoot(false)
    }

    void initEvent() {
        RxBus.subscribe(OnDepartmentAddedEvent.class){
            departmentProperties.add(it.departmentItem.toProperty())
            departmentTable.refresh()
        }
        RxBus.subscribe(OnEmployeeAddedEvent.class){
            employeeProperties.add(it.employeeItem.toProperty())
            employeeTable.refresh()
        }
        InformantRegistry.instance.addListener({messageArea.appendText("$it\n")})
    }

    /**
     * 设置文件选择器的点击
     * @param button
     * @param stage
     * @param closure
     * @return
     */
    def setButtonMouseClicked(button,stage,closure){
        button.setOnMouseClicked({
            final FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("请选择一个excel考勤文件");
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
            fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Excel", "*.xlsx","*.xls"));
            def file=fileChooser.showOpenDialog(stage)
            if(file&&file.exists()){closure(file)}
        })
    }

    /**
     * 处理考勤文件
     * @param file
     */
    def processAttendanceFile(File file) {
        if(!file.name.endsWith(".xlsx")&&!file.name.endsWith(".xls")){
            snackBar.fireEvent(new JFXSnackbar.SnackbarEvent("请选择一个excel文件!",null,2000, null))
        } else {
            Observable.create({ sub ->
                def attendanceItems=new ExcelReader().attendanceRead(file)
                def selectDepartment=departmentTable.selectionModel.selectedItem.value.toItem()
                def selectEmployeeItems=employeeItems.findAll {it.departmentId==selectDepartment.id}
                def analyser=new Analyser(attendanceItems,selectDepartment,selectEmployeeItems)
                def result=analyser.result()
                def excelWriter=new ExcelWriter(analyser.startDateTime,analyser.endDateTime,result,selectDepartment,selectEmployeeItems)
                excelWriter.writeExcel()
                sub.onNext(true)
                sub.onCompleted()
            }).subscribeOn(Schedulers.io()).
                    observeOn(MainThreadSchedulers.mainThread()).
                    subscribe({

                    }, { e -> e.printStackTrace() })
        }
    }

    public void handleDepartmentClick(Event event) {
        employeeTable.setPredicate({ it.value.departmentId.get()==departmentTable.selectionModel.selectedItem.value.id.get() })
    }

    public void handleAboutAction(ActionEvent actionEvent) {
        //TODO 关于
    }

    public void handleNewDepartmentAction(ActionEvent actionEvent) {
        StageManager.instance.newStage(getClass().getClassLoader().getResource("fxml/add_department.fxml"),true, 640, 720)?.show()
    }

    public void handleNewEmployeeAction(ActionEvent actionEvent) {
        StageManager.instance.newStage(getClass().getClassLoader().getResource("fxml/add_employee.fxml"), 640, 720)?.show()
    }

    public void handleHolidayAction(ActionEvent actionEvent) {

    }

}