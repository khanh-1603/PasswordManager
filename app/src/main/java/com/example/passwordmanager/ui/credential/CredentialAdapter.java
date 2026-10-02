package com.example.passwordmanager.ui.credential;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.passwordmanager.R;
import com.example.passwordmanager.data.model.CredentialItem;
import com.example.passwordmanager.databinding.ItemCredentialBinding;

import java.util.List;

public class CredentialAdapter
        extends RecyclerView.Adapter<CredentialAdapter.CredentialViewHolder> {
    private final Context context;
    private final List<CredentialItem> credentialList;

    private final OnCredentialClickListener listener;
    public CredentialAdapter(
            Context context,
            List<CredentialItem> credentialList,
            OnCredentialClickListener listener
    ) {
        this.context = context;
        this.credentialList = credentialList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public CredentialViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        ItemCredentialBinding binding = ItemCredentialBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
        );

        return new CredentialViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(
            @NonNull CredentialViewHolder holder,
            int position
    ) {
        CredentialItem item = credentialList.get(position);

        holder.txtTitle.setText(item.getTitle());
        holder.txtUsername.setText(item.getUsername());
        String domain = item.getAutofillDomain();
        String target = (domain != null && !domain.isEmpty())
                ? domain
                : item.getAutofillPackage();

        if (target == null || target.isEmpty()) {
            holder.txtUrl.setVisibility(View.GONE);
        } else {
            holder.txtUrl.setText(target);
            holder.txtUrl.setVisibility(View.VISIBLE);
        }
        holder.itemView.setOnClickListener(v -> listener.onCredentialClick(item));
    }

    @Override
    public int getItemCount() {
        return credentialList.size();
    }

    public void updateList(List<CredentialItem> newList) {
        DiffUtil.DiffResult diffResult =
                DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return credentialList.size(); }
            @Override public int getNewListSize() { return newList.size(); }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return credentialList.get(oldPos).getId()
                        .equals(newList.get(newPos).getId());
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return credentialList.get(oldPos).equals(newList.get(newPos));
            }
        });

        credentialList.clear();
        credentialList.addAll(newList);
        diffResult.dispatchUpdatesTo(this);
    }

    public interface OnCredentialClickListener {
        void onCredentialClick(CredentialItem credential);
    }

    public static class CredentialViewHolder
            extends RecyclerView.ViewHolder {

        private final TextView txtTitle;
        private final TextView txtUsername;
        private final TextView txtUrl;

        public CredentialViewHolder(@NonNull ItemCredentialBinding binding) {
            super(binding.getRoot());

            txtTitle = binding.tvItemTitle;
            txtUsername = binding.tvItemSubtitle;
            txtUrl = binding.tvItemUrl;
        }
    }
}
