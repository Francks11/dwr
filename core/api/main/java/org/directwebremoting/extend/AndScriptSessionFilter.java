/*
 * Copyright 2005 Joe Walker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.directwebremoting.extend;

import org.directwebremoting.ScriptSession;
import org.directwebremoting.ScriptSessionFilter;

/**
 * A {@link ScriptSessionFilter} that only allows {@link ScriptSession}s
 * that match pass both the given {@link ScriptSessionFilter}s
 */
public class AndScriptSessionFilter implements ScriptSessionFilter
{
    public AndScriptSessionFilter(ScriptSessionFilter left, ScriptSessionFilter right)
    {
        this.left = left;
        this.right = right;
    }

    /* (non-Javadoc)
     * @see org.directwebremoting.ScriptSessionFilter#match(org.directwebremoting.ScriptSession)
     */
    public boolean match(ScriptSession session)
    {
        return left.match(session) && right.match(session);
    }

    private final ScriptSessionFilter left;

    private final ScriptSessionFilter right;
}
