package com.example.superadmin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superadmin.core.CompanyDataHelper
import com.google.firebase.database.*

class NewConnectionEmployeeConfigActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private val employeeList = mutableListOf<EmployeeStatus>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connection_employee_config)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        recycler = findViewById(R.id.recyclerEmployeeConfig)
        recycler.layoutManager = LinearLayoutManager(this)

        loadEmployees()
    }

    private fun loadEmployees() {
        val compId = CompanyDataHelper.getCurrentCompanyId(this) ?: "OKA001"
        val companyActiveRef = FirebaseDatabase.getInstance().getReference("officeSettings").child(compId).child("new_connections/active_employees")
        val rootActiveRef = FirebaseDatabase.getInstance().getReference("officeSettings/new_connections/active_employees")

        FirebaseDatabase.getInstance().getReference("employees").get().addOnSuccessListener { empSnapshot ->
            companyActiveRef.get().addOnSuccessListener { activeSnapshot ->
                rootActiveRef.get().addOnSuccessListener { rootActiveSnapshot ->
                    employeeList.clear()
                    val uniqueEmpMap = mutableMapOf<String, String>()

                    for (child in empSnapshot.children) {
                        if (!CompanyDataHelper.belongsToCurrentCompany(this, child)) continue
                        val rawName = child.child("employeeName").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() }
                            ?: child.child("name").getValue(String::class.java)
                            ?: continue
                        val normName = rawName.trim().lowercase()
                        if (!uniqueEmpMap.containsKey(normName)) {
                            uniqueEmpMap[normName] = rawName
                        }
                    }

                    for ((_, displayName) in uniqueEmpMap) {
                        val isActive = if (activeSnapshot.hasChild(displayName)) {
                            activeSnapshot.child(displayName).getValue(Boolean::class.java) ?: true
                        } else {
                            rootActiveSnapshot.child(displayName).getValue(Boolean::class.java) ?: true
                        }
                        employeeList.add(EmployeeStatus(displayName, isActive))
                    }

                    employeeList.sortBy { it.name }
                    recycler.adapter = ConfigAdapter(employeeList) { name, status ->
                        companyActiveRef.child(name).setValue(status)
                        rootActiveRef.child(name).setValue(status)
                    }
                }
            }
        }
    }

    data class EmployeeStatus(val name: String, var isActive: Boolean)

    class ConfigAdapter(private val list: List<EmployeeStatus>, val onToggle: (String, Boolean) -> Unit) : RecyclerView.Adapter<ConfigAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvEmployeeName)
            val switch: SwitchCompat = v.findViewById(R.id.switchActive)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_employee_config, parent, false))
        override fun getItemCount(): Int = list.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.name.text = item.name
            holder.switch.isChecked = item.isActive
            holder.switch.setOnCheckedChangeListener { _, isChecked -> onToggle(item.name, isChecked) }
        }
    }
}
