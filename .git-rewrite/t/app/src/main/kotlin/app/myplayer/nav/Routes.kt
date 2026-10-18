package app.myplayer.nav

object Routes {
    const val WELCOME = "welcome"        // 还没有任何服务器时的引导页
    const val ADD_SERVER = "addServer"   // 任务 1-13
    const val LOGIN = "login"            // 任务 1-13
    const val MAIN = "main"              // 登录后主界面（底部导航 4 tab，任务 2-8）
    const val PLAYER = "play"            // play/{itemId}（任务 2-2）
}
