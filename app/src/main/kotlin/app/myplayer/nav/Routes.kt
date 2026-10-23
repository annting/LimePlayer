package app.myplayer.nav

object Routes {
    const val WELCOME = "welcome"        // 还没有任何服务器时的引导页
    const val ADD_SERVER = "addServer"   // 任务 1-13
    const val EDIT_SERVER = "editServer" // editServer/{serverId}：修改已有服务器（名称 / 地址 / 源类型 / 凭据）
    const val MAIN = "main"              // 登录后主界面（底部导航 4 tab，任务 2-8）
    const val DETAIL = "detail"          // detail/{itemId}（任务 5-5）
    const val PERSON = "person"          // person/{personId}?name=xxx（详情页二期 §6.3.1）
    const val PLAYER = "play"            // play/{itemId}（任务 2-2）
}
