package com.secureguard.mdm.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.secureguard.mdm.R

/**
 * דיאלוג ייעודי לבקשת סיסמת ניהול מהמשתמש.
 * @param passwordError (אופציונלי) הודעת שגיאה להצגה אם הסיסמה שהוזנה שגויה.
 * @param onConfirm הפעולה שתתבצע עם הסיסמה שהוזנה.
 * @param onDismiss הפעולה שתתבצע כאשר הדיאלוג נסגר.
 */
@Composable
fun PasswordPromptDialog(
    passwordError: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
) {
    var passwordValue by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(text = stringResource(id = R.string.dialog_title_enter_password)) },
        text = {
            Column {
                Text(text = stringResource(id = R.string.dialog_description_enter_password))
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = passwordValue,
                    onValueChange = { passwordValue = it },
                    enabled = enabled,
                    label = { Text(stringResource(id = R.string.setup_password_label)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = passwordError != null,
                    trailingIcon = {
                        val image = if (passwordVisible) {
                            Icons.Filled.Visibility
                        } else {
                            Icons.Filled.VisibilityOff
                        }
                        val description = if (passwordVisible) "הסתר סיסמה" else "הצג סיסמה"
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            enabled = enabled,
                        ) {
                            Icon(imageVector = image, contentDescription = description)
                        }
                    },
                )
                passwordError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passwordValue) },
                enabled = enabled,
            ) {
                if (enabled) {
                    Text(stringResource(id = R.string.dialog_button_confirm))
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) {
                Text(stringResource(id = R.string.dialog_button_cancel))
            }
        },
    )
}
