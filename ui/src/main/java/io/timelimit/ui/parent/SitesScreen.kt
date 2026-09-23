package io.timelimit.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Button
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.timelimit.api.ChildHome
import io.timelimit.api.ParentApi
import io.timelimit.api.Sites
import io.timelimit.ui.R
import io.timelimit.ui.child.LocalChildColors

private const val EVERYTHING = "*"

/** Chrome's own filter format; a pasted address loses its scheme and trailing slash so that lists stay readable. */
private fun normalize(text: String) = text.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

/** P7: sites in Chrome on the child's tablets — the same as the web console's «Сайты». */
// @tag:new-ui @tag:url-filter
@Composable
fun SitesScreen(child: ChildHome, api: ParentApi, actions: ParentActions) {
    val colors = LocalChildColors.current
    val saved = child.sites

    if (saved == null) {
        Text(stringResource(R.string.parent_sites_unsupported), color = colors.refused)
        return
    }

    var address by rememberSaveable { mutableStateOf("") }
    val onlyAllowed = EVERYTHING in saved.block
    val blocked = saved.block - EVERYTHING
    val set = { sites: Sites, done: String -> actions(done) { api.setSites(sites) } }
    val allowedDone = stringResource(R.string.parent_site_allowed)
    val blockedDone = stringResource(R.string.parent_site_blocked)
    val removedDone = stringResource(R.string.parent_site_removed)
    val onlyDone = stringResource(R.string.parent_sites_only_allowed)
    val allDone = stringResource(R.string.parent_sites_all_but_blocked)
    val onDone = stringResource(R.string.parent_sites_on)
    val offDone = stringResource(R.string.parent_sites_off)

    fun add(toAllow: Boolean) {
        val site = normalize(address).takeIf { it.isNotEmpty() } ?: return
        val allow = saved.allow - site
        val block = saved.block - site
        set(Sites(true, if (toAllow) allow + site else allow, if (toAllow) block else block + site), (if (toAllow) allowedDone else blockedDone).format(site))
        address = ""
    }

    Text(stringResource(R.string.parent_sites_hint, child.name), color = colors.secondary, fontSize = 14.sp)

    Card {
        if (!saved.enabled) {
            Text(stringResource(R.string.parent_sites_disabled), color = colors.text)
            Button(onClick = { set(saved.copy(enabled = true), onDone) }) { Text(stringResource(R.string.parent_sites_enable)) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(6f))) {
            OutlinedButton(onClick = { set(saved.copy(block = blocked), allDone) }, enabled = onlyAllowed) { Text(stringResource(R.string.parent_sites_mode_all)) }
            OutlinedButton(onClick = { set(saved.copy(block = listOf(EVERYTHING) + blocked), onlyDone) }, enabled = !onlyAllowed) { Text(stringResource(R.string.parent_sites_mode_only)) }
        }
    }

    Card {
        OutlinedTextField(address, { address = it }, placeholder = { Text("scratch.mit.edu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(6f))) {
            Button(onClick = { add(true) }, enabled = normalize(address).isNotEmpty()) { Text(stringResource(R.string.parent_sites_allow)) }
            OutlinedButton(onClick = { add(false) }, enabled = normalize(address).isNotEmpty()) { Text(stringResource(R.string.parent_sites_block)) }
        }
    }

    listOf(
        Triple(R.string.parent_sites_allowed_list, saved.allow, true),
        Triple(R.string.parent_sites_blocked_list, blocked, false),
    ).filter { it.second.isNotEmpty() }.forEach { (title, items, isAllow) ->
        Card {
            Text(stringResource(title, items.size), color = colors.text, fontWeight = FontWeight.SemiBold)
            items.forEach { site ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(site, color = colors.text, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        set(if (isAllow) saved.copy(allow = saved.allow - site) else saved.copy(block = saved.block - site), removedDone.format(site))
                    }) { Text("×") }
                }
            }
        }
    }

    Text(stringResource(R.string.parent_sites_format), color = colors.secondary, fontSize = 13.sp)
    if (saved.enabled) TextButton(onClick = { set(saved.copy(enabled = false), offDone) }) { Text(stringResource(R.string.parent_sites_disable)) }
}

