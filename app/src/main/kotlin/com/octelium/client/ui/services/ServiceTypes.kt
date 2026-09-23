package com.octelium.client.ui.services

import androidx.annotation.DrawableRes
import com.octelium.client.R
import com.octelium.client.ui.theme.Palette
import octelium.api.main.user.v1.Userv1.Service.Spec.Type

data class ServiceTypeStyle(
    @DrawableRes val icon: Int,
    val palette: Palette,
)

fun getServiceTypeStyle(type: Type): ServiceTypeStyle = when (type) {
    Type.WEB -> ServiceTypeStyle(R.drawable.ic_app_window, Palette.SKY)
    Type.HTTP -> ServiceTypeStyle(R.drawable.ic_earth, Palette.BLUE)
    Type.GRPC -> ServiceTypeStyle(R.drawable.ic_network, Palette.VIOLET)
    Type.SSH -> ServiceTypeStyle(R.drawable.ic_terminal, Palette.SLATE)
    Type.KUBERNETES -> ServiceTypeStyle(R.drawable.ic_kubernetes, Palette.INDIGO)
    Type.POSTGRES -> ServiceTypeStyle(R.drawable.ic_postgresql, Palette.CYAN)
    Type.MYSQL -> ServiceTypeStyle(R.drawable.ic_mysql, Palette.AMBER)
    Type.TCP -> ServiceTypeStyle(R.drawable.ic_cable, Palette.TEAL)
    Type.UDP -> ServiceTypeStyle(R.drawable.ic_radio, Palette.EMERALD)
    Type.DNS -> ServiceTypeStyle(R.drawable.ic_waypoints, Palette.GREEN)
    Type.SOCKS5 -> ServiceTypeStyle(R.drawable.ic_router, Palette.FUCHSIA)
    Type.RDP_WEB, Type.RDP -> ServiceTypeStyle(R.drawable.ic_monitor, Palette.ROSE)
    Type.LLM -> ServiceTypeStyle(R.drawable.ic_brain_circuit, Palette.PURPLE)
    Type.MCP -> ServiceTypeStyle(R.drawable.ic_mcp, Palette.ORANGE)
    else -> ServiceTypeStyle(R.drawable.ic_server, Palette.SLATE)
}
