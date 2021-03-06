/*
 * JEF - Copyright 2009-2010 Jiyi (mr.jiyi@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jef.database.jsqlparser.visitor;

import jef.database.jsqlparser.statement.SqlAppendable;

/**
 * An item in a "SELECT [...] FROM item1" statement.
 * (for example a table or a sub-select) 
 */
public interface FromItem extends SqlAppendable{

    public void accept(SelectItemVisitor fromItemVisitor);

    public String getAlias();

    public void setAlias(String alias);
   //返回表明定義不含Alias的部分 
    public String toWholeName();
}
