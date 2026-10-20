package com.mynok.myagent.data.repo

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * 全局唯一的 Preferences DataStore。
 *
 * `preferencesDataStore` 的委托**按属性**做单例，不是按文件名。
 * 同一个文件名如果在两个文件里各声明一次，就会产生两个 DataStoreImpl，
 * 第二个被访问时会抛：
 *
 *   IllegalStateException: There are multiple DataStores active for the same file
 *
 * 所以设置项（[SettingsStore]）和密钥（SecretStore）必须共用这一个委托。
 */
internal val Context.appDataStore by preferencesDataStore(name = "myagent_settings")
