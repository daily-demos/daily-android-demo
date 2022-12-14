package co.daily.core.dailydemo

interface DemoStateListener {
    fun onStateChanged(newState: DemoState)
    fun onError(msg: String)
}
