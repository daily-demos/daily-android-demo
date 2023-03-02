package co.daily.core.dailydemo

import android.app.Activity
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.LinearLayoutCompat
import co.daily.CallClient
import co.daily.CallClientCoroutineWrapper
import co.daily.exception.RequestFailedException
import co.daily.model.RequestListener
import co.daily.model.RequestListenerWithData
import co.daily.model.RequestResult
import co.daily.model.RequestResultWithData
import co.daily.settings.Update
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.math.roundToInt
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVisibility
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSubtypeOf

private const val TAG = "DeveloperOptionsDialog"

/**
 * This class shows a dialog which lists all the API methods exposed by the CallClient
 * and CallClientCoroutineWrapper. This is done using reflection, and allows a
 * developer to invoke any of the API methods at runtime.
 */
object DeveloperOptionsDialog {

    private val jsonPrettyPrint = Json { prettyPrint = true }

    fun show(activity: Activity, callClient: CallClient) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.call_option_developer_options_title)
            .setView(R.layout.developer_options)
            .setNegativeButton(R.string.close_dialog, null)
            .create()

        dialog.show()

        val list: LinearLayoutCompat = dialog.findViewById(R.id.developer_options_list)!!

        addInstanceButton(activity, list, callClient)
        addInstanceButton(activity, list, CallClientCoroutineWrapper(callClient))
    }

    private inline fun <reified E> addInstanceButton(
        activity: Activity,
        list: ViewGroup,
        instance: E
    ) {
        val button = MaterialButton(activity)
        button.setText(E::class.simpleName)
        button.isAllCaps = false
        list.addView(button)

        button.setOnClickListener {
            showMethodListDialog(activity, instance)
        }
    }

    private inline fun <reified E> showMethodListDialog(activity: Activity, instance: E) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.call_option_developer_options_title)
            .setView(R.layout.developer_options)
            .setNegativeButton(R.string.close_dialog, null)
            .create()

        dialog.show()

        val list: LinearLayoutCompat = dialog.findViewById(R.id.developer_options_list)!!

        E::class.declaredFunctions.filter { it.visibility == KVisibility.PUBLIC }.forEach { method ->
            val button = MaterialButton(activity)
            button.setText(method.name)
            button.isAllCaps = false
            list.addView(button)

            button.setOnClickListener {
                showMethodCallDialog(activity, instance, method)
            }
        }
    }

    private fun <E> showMethodCallDialog(activity: Activity, instance: E, method: KCallable<Any?>) {

        fun isSerializable(classifier: KClassifier?): Boolean {
            if (classifier is KClass<*>) {
                return classifier.hasAnnotation<kotlinx.serialization.Serializable>() ||
                    classifier.isSubclassOf(Map::class) ||
                    classifier.isSubclassOf(List::class) ||
                    classifier.isSubclassOf(Update::class)
            } else {
                return false
            }
        }

        fun makeConcrete(type: KType): KType {

            val args = type.arguments.map {
                KTypeProjection(
                    variance = it.variance,
                    type = it.type?.run { makeConcrete(this) }
                )
            }.toCollection(ArrayList())

            when (type.classifier) {
                Map::class -> {
                    return HashMap::class.createType(
                        arguments = args,
                        nullable = type.isMarkedNullable,
                        annotations = type.annotations
                    )
                }
                List::class -> {
                    return ArrayList::class.createType(
                        arguments = args,
                        nullable = type.isMarkedNullable,
                        annotations = type.annotations
                    )
                }
                Update::class -> {
                    return args[0].type!!
                }
                else -> {
                    return type
                }
            }
        }

        val argInputs = HashMap<KParameter, () -> Any?>()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(method.name)
            .setView(R.layout.developer_options)
            .setPositiveButton("Invoke") { dialog, which ->
                MainScope().launch {
                    try {

                        val result: Any? = if (method.isSuspend) {
                            method.callSuspendBy(argInputs.mapValues { it.value() })
                        } else {
                            method.callBy(argInputs.mapValues { it.value() })
                        }

                        if (method.returnType.classifier != Unit::class) {

                            val resultString = if (isSerializable(method.returnType.classifier)) {
                                jsonPrettyPrint.encodeToString(serializer(method.returnType), result)
                            } else {
                                result?.toString() ?: "<null>"
                            }

                            showMessageDialog(activity, method.name, resultString)
                            Log.i(
                                TAG,
                                "Return value for " + method.name + ": " + resultString
                            )
                        }
                    } catch (e: Exception) {
                        showMessageDialog(activity, method.name, e.toString())
                        Log.e(TAG, "Invocation failed", e)
                    }
                }
            }
            .setNegativeButton(R.string.close_dialog, null)
            .create()

        dialog.show()

        val list: LinearLayoutCompat = dialog.findViewById(R.id.developer_options_list)!!

        try {
            method.parameters.forEach { param ->
                if (param.kind == KParameter.Kind.INSTANCE) {
                    argInputs.put(param) { instance }
                } else {

                    var hint = param.name + " ("

                    if (param.type.isMarkedNullable) {
                        hint += "optional, "
                    }

                    if (param.type.classifier == String::class) {

                        hint += "string)"

                        val inputBox = EditText(activity)
                        inputBox.hint = hint
                        list.addView(inputBox)

                        argInputs.put(param) {
                            val value = inputBox.text?.toString()

                            if (param.type.isMarkedNullable) {
                                value?.takeUnless { it.isEmpty() }
                            } else {
                                value!!
                            }
                        }
                    } else if (param.type.isSubtypeOf(
                            Enum::class.createType(arguments = listOf(KTypeProjection.STAR))
                        )
                    ) {

                        hint += "enum)"

                        val items = (param.type.classifier as KClass<*>).java.enumConstants

                        val prompt = TextView(activity)
                        prompt.setText(hint)
                        list.addView(prompt)

                        prompt.setPadding(
                            0,
                            TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                10f,
                                activity.resources.displayMetrics
                            ).roundToInt(),
                            0,
                            0
                        )

                        val spinner = Spinner(activity)
                        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
                        list.addView(spinner)

                        argInputs.put(param) {
                            spinner.selectedItem
                        }
                    } else if (param.type.classifier == RequestListener::class) {

                        argInputs.put(param) {
                            RequestListener { result ->
                                showResultDialog(
                                    activity,
                                    method.name,
                                    result
                                )
                            }
                        }
                    } else if (param.type.classifier == RequestListenerWithData::class) {

                        argInputs.put(param) {
                            RequestListenerWithData<Any> { result ->
                                showResultDialog(
                                    activity,
                                    method.name,
                                    result
                                )
                            }
                        }
                    } else if (isSerializable(param.type.classifier)) {

                        hint += "json " + param.type.toString() + ")"

                        val inputBox = EditText(activity)
                        inputBox.inputType = EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                        inputBox.hint = hint
                        list.addView(inputBox)

                        try {
                            val s = serializer(makeConcrete(param.type))

                            argInputs.put(param) {
                                inputBox.text?.toString()?.takeUnless { it.isEmpty() }
                                    ?.run { Json.decodeFromString(s, this) }
                            }
                        } catch (e: Exception) {
                            throw RuntimeException("No serializer for parameter type " + param.name, e)
                        }
                    } else {
                        throw RuntimeException("Cannot handle type of parameter " + param.name)
                    }
                }
            }
        } catch (e: Exception) {
            showMessageDialog(activity, method.name, "Could not invoke method: " + e.message)
            Log.e(TAG, "Could not invoke method in developer options", e)
            dialog.dismiss()
        }
    }

    private fun showMessageDialog(activity: Activity, title: String, msg: String) {

        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setNegativeButton(R.string.close_dialog, null)
            .create()
            .show()
    }

    private fun showResultDialog(activity: Activity, title: String, result: RequestResult) {

        val text = result.error?.msg?.run { "Error: $this" } ?: "Success!"

        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(text)
            .setNegativeButton(R.string.close_dialog, null)
            .create()
            .show()
    }

    private fun showResultDialog(activity: Activity, title: String, result: RequestResultWithData<Any>) {

        val text = try {
            "Success: " + result.getResultOrThrow().toString()
        } catch (e: RequestFailedException) {
            "Error: " + e.message
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(text)
            .setNegativeButton(R.string.close_dialog, null)
            .create()
            .show()
    }
}
