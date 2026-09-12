/*
 * TimeLimit Copyright <C> 2019 - 2026 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.ui.manage.child.urlfilter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.timelimit.android.R
import io.timelimit.android.ui.model.managechild.ManageChildUrlFilter

// @tag:url-filter
@Composable
fun UrlFilterScreen(content: ManageChildUrlFilter.Screen, modifier: Modifier = Modifier) {
    var enabled by rememberSaveable(content.current) { mutableStateOf(content.current?.enabled ?: false) }
    var allowText by rememberSaveable(content.current) { mutableStateOf(content.current?.allow.orEmpty().joinToString("\n")) }
    var blockText by rememberSaveable(content.current) { mutableStateOf(content.current?.block.orEmpty().joinToString("\n")) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.url_filter_text))

        if (!content.serverSupportsFilter) Text(stringResource(R.string.url_filter_server_too_old))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.url_filter_enable), Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        OutlinedTextField(
            value = allowText,
            onValueChange = { allowText = it },
            label = { Text(stringResource(R.string.url_filter_allow)) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = blockText,
            onValueChange = { blockText = it },
            label = { Text(stringResource(R.string.url_filter_block)) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { content.save(enabled, allowText, blockText) },
            enabled = content.serverSupportsFilter,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.generic_save))
        }
    }
}
