/*
 * Copyright (c) 2024, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.banktags.tabs;

import java.util.Arrays;
import lombok.Getter;
import lombok.NonNull;

public class Layout
{
	@Getter
	private final String tag;
	/**
	 * Each slot can contain multiple candidate item ids.
	 *
	 * A {@code null} entry is an empty slot.
	 * Otherwise, the entry is an array of item ids in priority order.
	 */
	private int[][] layout;

	public Layout(String tag)
	{
		this.tag = tag;
		this.layout = new int[0][];
	}

	public Layout(String tag, @NonNull int[][] layout)
	{
		this.tag = tag;
		this.layout = cloneLayout(layout);
	}

	public Layout(Layout other)
	{
		tag = other.tag;
		layout = cloneLayout(other.layout);
	}

	public int[][] getLayout()
	{
		return cloneLayout(layout);
	}

	public int[] getItemsAtPos(int pos)
	{
		if (pos < 0 || pos >= layout.length)
		{
			return null;
		}

		int[] v = layout[pos];
		return v == null ? null : v.clone();
	}

	public int getItemAtPos(int pos)
	{
		int[] items = getItemsAtPos(pos);
		if (items == null || items.length == 0)
		{
			return -1;
		}
		return items[0];
	}

	public void setItemAtPos(int itemId, int pos)
	{
		if (pos < 0)
		{
			return;
		}

		if (layout == null)
		{
			layout = new int[pos + 1][];
		}
		else if (pos >= layout.length)
		{
			int[][] n = Arrays.copyOf(layout, pos + 1);
			layout = n;
		}

		layout[pos] = itemId == -1 ? null : new int[]{itemId};
	}

	public void setItemsAtPos(@NonNull int[] itemIds, int pos)
	{
		if (pos < 0)
		{
			return;
		}

		if (layout == null)
		{
			layout = new int[pos + 1][];
		}
		else if (pos >= layout.length)
		{
			layout = Arrays.copyOf(layout, pos + 1);
		}

		layout[pos] = itemIds.length == 0 ? null : itemIds.clone();
	}

	public void addItem(int itemId)
	{
		addItemAfter(itemId, 0);
	}

	public void addItemAfter(int itemId, int pos)
	{
		int i;
		for (i = pos; i < layout.length; ++i)
		{
			if (layout[i] == null)
			{
				layout[i] = new int[]{itemId};
				return;
			}
		}

		resize(Math.max(pos + 1, layout.length + 1));
		layout[i] = new int[]{itemId};
	}

	public void removeItem(int itemId)
	{
		for (int i = 0; i < layout.length; ++i)
		{
			int[] v = layout[i];
			if (v == null)
			{
				continue;
			}

			int idx = indexOf(v, itemId);
			if (idx == -1)
			{
				continue;
			}

			if (v.length == 1)
			{
				layout[i] = null;
			}
			else
			{
				int[] n = new int[v.length - 1];
				System.arraycopy(v, 0, n, 0, idx);
				System.arraycopy(v, idx + 1, n, idx, v.length - idx - 1);
				layout[i] = n;
			}
		}
	}

	public void removeItemAtPos(int pos)
	{
		if (pos < 0 || pos >= layout.length)
		{
			return;
		}

		layout[pos] = null;
	}

	/**
	 * Remove {@code itemId} from the candidate list at {@code pos} only.
	 */
	public void removeItemFromPos(int itemId, int pos)
	{
		if (itemId == -1 || pos < 0 || pos >= layout.length)
		{
			return;
		}

		int[] v = layout[pos];
		if (v == null)
		{
			return;
		}

		int idx = indexOf(v, itemId);
		if (idx == -1)
		{
			return;
		}

		if (v.length == 1)
		{
			layout[pos] = null;
			return;
		}

		layout[pos] = removeAtIndex(v, idx);
	}

	/**
	 * Insert {@code itemId} into the candidate list at {@code pos} before {@code insertIdx}.
	 * <p>
	 * If {@code itemId} already exists in the list, it is moved to the new position.
	 */
	public void insertItemBeforeIndexAtPos(int itemId, int pos, int insertIdx)
	{
		if (itemId == -1 || pos < 0)
		{
			return;
		}

		if (layout == null)
		{
			layout = new int[pos + 1][];
		}
		else if (pos >= layout.length)
		{
			layout = Arrays.copyOf(layout, pos + 1);
		}

		int[] v = layout[pos];
		if (v == null || v.length == 0)
		{
			layout[pos] = new int[]{itemId};
			return;
		}

		int len = v.length;
		insertIdx = Math.max(0, Math.min(insertIdx, len));

		int existingIdx = indexOf(v, itemId);
		if (existingIdx != -1)
		{
			// Remove it first; insertion index shifts left if the removed element was before the target.
			if (existingIdx < insertIdx)
			{
				insertIdx--;
			}
			len--;
		}

		int[] base = (existingIdx == -1) ? v : removeAtIndex(v, existingIdx);
		int[] n = new int[len + 1];
		System.arraycopy(base, 0, n, 0, insertIdx);
		n[insertIdx] = itemId;
		System.arraycopy(base, insertIdx, n, insertIdx + 1, base.length - insertIdx);
		layout[pos] = n;
	}

	/**
	 * Add {@code itemId} as the highest-priority candidate for {@code pos}.
	 *
	 * If {@code itemId} is already present in the candidate list, it is moved to the front.
	 */
	public void addItemToFrontAtPos(int itemId, int pos)
	{
		if (itemId == -1 || pos < 0)
		{
			return;
		}

		if (layout == null)
		{
			layout = new int[pos + 1][];
		}
		else if (pos >= layout.length)
		{
			layout = Arrays.copyOf(layout, pos + 1);
		}

		int[] v = layout[pos];
		if (v == null || v.length == 0)
		{
			layout[pos] = new int[]{itemId};
			return;
		}

		int idx = indexOf(v, itemId);
		if (idx == 0)
		{
			return;
		}

		int[] n;
		if (idx == -1)
		{
			n = new int[v.length + 1];
			n[0] = itemId;
			System.arraycopy(v, 0, n, 1, v.length);
		}
		else
		{
			n = new int[v.length];
			n[0] = itemId;
			System.arraycopy(v, 0, n, 1, idx);
			System.arraycopy(v, idx + 1, n, idx, v.length - idx - 1);
		}

		layout[pos] = n;
	}

	private static int[] removeAtIndex(int[] arr, int idx)
	{
		int[] n = new int[arr.length - 1];
		System.arraycopy(arr, 0, n, 0, idx);
		System.arraycopy(arr, idx + 1, n, idx, arr.length - idx - 1);
		return n;
	}

	void swap(int sidx, int tidx)
	{
		int[] sid = layout[sidx];
		layout[sidx] = layout[tidx];
		layout[tidx] = sid;
	}

	void insert(int sidx, int tidx)
	{
		int[] sid = layout[sidx];
		if (sidx < tidx)
		{
			// Shift items down to the next blank spot
			int i = tidx;
			while (i > sidx && layout[i] != null)
			{
				--i;
			}

			layout[sidx] = null;
			System.arraycopy(layout, i + 1, layout, i, tidx - i);
			layout[tidx] = sid;
		}
		else if (sidx > tidx)
		{
			// Shift items up to the next blank spot
			int i = tidx;
			while (i < sidx && layout[i] != null)
			{
				++i;
			}

			layout[sidx] = null;
			System.arraycopy(layout, tidx, layout, tidx + 1, i - tidx);
			layout[tidx] = sid;
		}
	}

	public int count(int itemId)
	{
		int c = 0;
		for (int[] value : layout)
		{
			if (value != null && indexOf(value, itemId) != -1)
			{
				++c;
			}
		}
		return c;
	}

	public int size()
	{
		return layout.length;
	}

	public void resize(int size)
	{
		layout = Arrays.copyOf(layout, size);
	}

	private static int indexOf(int[] arr, int v)
	{
		for (int i = 0; i < arr.length; ++i)
		{
			if (arr[i] == v)
			{
				return i;
			}
		}
		return -1;
	}

	private static int[][] cloneLayout(int[][] in)
	{
		int[][] out = new int[in.length][];
		for (int i = 0; i < in.length; ++i)
		{
			out[i] = in[i] == null ? null : in[i].clone();
		}
		return out;
	}
}
